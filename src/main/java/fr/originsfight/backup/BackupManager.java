package fr.originsfight.backup;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sauvegarde automatique de la base de données PENDANT que le serveur tourne.
 *
 * <p>Tourne UNIQUEMENT sur l'hôte H2 (le Faction, {@code database.server.enabled: true}) : le Minage
 * est un client H2 et ne doit pas sauvegarder la base partagée. Chaque jour, à l'heure configurée,
 * produit {@code Backup/Back_<date>.zip} contenant :
 * <ul>
 *   <li>{@code h2_central.sql} — export H2 cohérent via {@code SCRIPT TO} sur la connexion vivante ;</li>
 *   <li>{@code mariadb_luckperms.sql} — dump MariaDB via {@code mariadb-dump} (optionnel).</li>
 * </ul>
 * Purge les archives plus vieilles que {@code backup.retention-days}. Tout le travail (SQL + dump +
 * zip) s'exécute sur un thread asynchrone pour ne jamais bloquer le serveur.
 */
public class BackupManager {

    private final OriginsFightCore plugin;
    private BukkitTask task;
    private volatile boolean running = false;

    /** Intervalle entre deux sauvegardes auto (ms). 0 si l'auto-sauvegarde n'est pas active. */
    private volatile long intervalMillis = 0L;
    /** Horodatage (epoch ms) de la prochaine sauvegarde auto programmée. 0 si non programmée. */
    private volatile long nextBackupAt = 0L;

    public BackupManager(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) return;

        // Seul l'hôte H2 (le Faction) sauvegarde la base partagée.
        if (!plugin.getConfig().getBoolean("database.server.enabled", false)) {
            plugin.getLogger().info("[Backup] Ce serveur n'héberge pas H2 → sauvegarde auto désactivée (gérée par le Faction).");
            return;
        }

        int hours = Math.max(1, plugin.getConfig().getInt("backup.interval-hours", 6));
        long period = 20L * 3600L * hours; // période en ticks
        this.intervalMillis = 3600_000L * hours;
        this.nextBackupAt = System.currentTimeMillis() + intervalMillis;
        // Première sauvegarde après un intervalle complet (évite un backup à chaque redémarrage).
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::scheduledRun, period, period);
        plugin.getLogger().info("[Backup] Sauvegarde automatique activée (toutes les " + hours + " h).");
    }

    /** Tâche cyclique : recale l'échéance suivante puis lance la sauvegarde. */
    private void scheduledRun() {
        this.nextBackupAt = System.currentTimeMillis() + intervalMillis;
        runBackup();
    }

    /** true si l'auto-sauvegarde tourne sur ce serveur (hôte H2). */
    public boolean isAutoEnabled() {
        return task != null;
    }

    /** Millisecondes restantes avant la prochaine sauvegarde auto, ou -1 si l'auto n'est pas active. */
    public long getMillisUntilNext() {
        if (task == null || nextBackupAt <= 0L) return -1L;
        return Math.max(0L, nextBackupAt - System.currentTimeMillis());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nextBackupAt = 0L;
    }

    /**
     * Lance une sauvegarde immédiate (déjà appelé depuis un thread async, ou via la commande staff).
     */
    public synchronized void runBackup() {
        if (running) {
            plugin.getLogger().warning("[Backup] Une sauvegarde est déjà en cours — ignorée.");
            return;
        }
        running = true;
        try {
            // Horodatage date + heure : permet plusieurs sauvegardes par jour sans collision.
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date());
            File backupDir = resolveBackupDir();
            backupDir.mkdirs();
            File staging = new File(backupDir, ".staging_" + System.currentTimeMillis());
            staging.mkdirs();

            boolean h2Ok = dumpH2(staging);
            boolean mariaOk = dumpMariaDB(staging);

            if (!h2Ok && !mariaOk) {
                plugin.getLogger().severe("[Backup] Aucune source sauvegardée — archive non créée.");
                deleteRecursive(staging);
                return;
            }

            File zip = new File(backupDir, "Back_" + stamp + ".zip");
            zipDirectory(staging, zip);
            deleteRecursive(staging);
            plugin.getLogger().info("[Backup] Archive créée : " + zip.getName()
                    + " (" + (zip.length() / 1024) + " Ko) [H2=" + h2Ok + ", MariaDB=" + mariaOk + "]");

            prune(backupDir);
        } catch (Exception e) {
            plugin.getLogger().severe("[Backup] Échec de la sauvegarde : " + e.getMessage());
        } finally {
            running = false;
        }
    }

    // ── H2 : export cohérent via la connexion vivante ──────────────────────────

    private boolean dumpH2(File staging) {
        // 1) Export SQL cohérent via la connexion vivante (SCRIPT TO).
        if (plugin.getCoreDatabase() != null && plugin.getCoreDatabase().isAvailable()) {
            File out = new File(staging, "h2_central.sql");
            String path = out.getAbsolutePath().replace('\\', '/').replace("'", "''");
            try (Connection c = plugin.getCoreDatabase().getConnection();
                 Statement st = c.createStatement()) {
                // SCRIPT TO : H2 écrit un dump SQL cohérent de toute la base (transactionnel).
                st.execute("SCRIPT TO '" + path + "'");
                if (out.exists() && out.length() > 0) return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[Backup] SCRIPT TO H2 échoué (" + e.getMessage()
                        + ") — repli sur copie du fichier .mv.db.");
            }
        }

        // 2) Repli : copie du fichier H2 (crash-consistent, recouvrable par MVStore).
        String name = plugin.getConfig().getString("database.name", "central");
        File mv = new File(new File(plugin.getDataFolder(), "data"), name + ".mv.db");
        if (mv.exists()) {
            try {
                java.nio.file.Files.copy(mv.toPath(), new File(staging, name + ".mv.db").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[Backup] Copie du fichier H2 échouée : " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("[Backup] Fichier H2 introuvable : " + mv.getPath());
        }
        return false;
    }

    // ── MariaDB : dump logique via mariadb-dump (optionnel) ────────────────────

    private boolean dumpMariaDB(File staging) {
        if (!plugin.getConfig().getBoolean("backup.mariadb.enabled", true)) return false;

        String exe = plugin.getConfig().getString("backup.mariadb.dump-exe",
                "MariaDB/mariadb-11.4.5-winx64/bin/mariadb-dump.exe");
        File exeFile = new File(exe);
        if (!exeFile.isAbsolute()) exeFile = new File(".", exe);
        if (!exeFile.exists()) {
            plugin.getLogger().warning("[Backup] mariadb-dump introuvable (" + exeFile.getPath() + ") — MariaDB ignoré.");
            return false;
        }

        String user = plugin.getConfig().getString("backup.mariadb.user", "root");
        String pass = plugin.getConfig().getString("backup.mariadb.password", "");
        String db = plugin.getConfig().getString("backup.mariadb.database", "luckperms");
        File out = new File(staging, "mariadb_" + db + ".sql");

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(exeFile.getAbsolutePath());
        cmd.add("-u");
        cmd.add(user);
        if (pass != null && !pass.isEmpty()) cmd.add("-p" + pass);
        cmd.add("--single-transaction");
        cmd.add("--routines");
        cmd.add("--events");
        cmd.add("--databases");
        cmd.add(db);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(out);
            pb.redirectErrorStream(false);
            pb.redirectError(new File(staging, "mariadb_dump.err"));
            Process p = pb.start();
            int code = p.waitFor();
            File err = new File(staging, "mariadb_dump.err");
            if (code == 0 && out.exists() && out.length() > 0) {
                if (err.exists()) err.delete();
                return true;
            }
            plugin.getLogger().warning("[Backup] Dump MariaDB échoué (code=" + code + "). MariaDB démarré ?");
            if (out.exists()) out.delete();
            if (err.exists()) err.delete();
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("[Backup] Dump MariaDB échoué : " + e.getMessage());
            return false;
        }
    }

    // ── Utilitaires ────────────────────────────────────────────────────────────

    private File resolveBackupDir() {
        String folder = plugin.getConfig().getString("backup.folder", "Backup");
        File f = new File(folder);
        return f.isAbsolute() ? f : new File(".", folder);
    }

    /**
     * Supprime les sauvegardes en trop : au-delà de {@code backup.max-backups} (les plus anciennes
     * partent) ET au-delà de {@code backup.retention-days} jours. Mettre une valeur à 0 désactive ce
     * critère. Garantit que les plus vieux Back_*.zip sont bien retirés du dossier.
     */
    private void prune(File backupDir) {
        File[] arr = backupDir.listFiles((dir, name) -> name.startsWith("Back_") && name.endsWith(".zip"));
        if (arr == null || arr.length == 0) return;

        java.util.List<File> files = new java.util.ArrayList<>(java.util.Arrays.asList(arr));
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified())); // plus récents d'abord

        int maxBackups = plugin.getConfig().getInt("backup.max-backups", 28);
        int retentionDays = plugin.getConfig().getInt("backup.retention-days", 14);
        long cutoff = retentionDays > 0
                ? System.currentTimeMillis() - retentionDays * 24L * 3600_000L
                : Long.MIN_VALUE;

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            boolean tooMany = maxBackups > 0 && i >= maxBackups;
            boolean tooOld = f.lastModified() < cutoff;
            if ((tooMany || tooOld) && f.delete()) {
                plugin.getLogger().info("[Backup] Purge : " + f.getName()
                        + (tooMany ? " (au-delà de " + maxBackups + " sauvegardes)"
                        : " (plus de " + retentionDays + " jours)"));
            }
        }
    }

    private void zipDirectory(File dir, File zipFile) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            byte[] buf = new byte[8192];
            for (File f : files) {
                if (!f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
