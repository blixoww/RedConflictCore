package fr.originsfight.job;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Snapshot figé des classements métiers.
 *
 * <p>Contrairement à un classement « live », le top affiché aux joueurs
 * (commande {@code /metier top} et onglet classement du GUI) est lu depuis un
 * instantané mis en cache. Cet instantané est recalculé :
 * <ul>
 *   <li>automatiquement toutes les {@value #REFRESH_HOURS} heures ;</li>
 *   <li>à chaque démarrage du serveur (donc après chaque redémarrage) ;</li>
 *   <li>à la demande via {@code /metier topupdate} (staff).</li>
 * </ul>
 *
 * <p>Le snapshot est persisté en base (tables {@code job_top_snapshot} /
 * {@code job_top_meta}) afin d'être disponible immédiatement au démarrage,
 * avant même le premier recalcul.
 */
public class JobTopManager {

    private static final Logger LOG = Logger.getLogger("Jobs");

    /** Période de rafraîchissement automatique, en heures. */
    public static final long REFRESH_HOURS = 24L;

    /** Catégories de classement exposées (correspondent aux clés du packet JOB_TOP). */
    private static final String[] CATEGORIES = { "ALL", "MINER", "FARMER", "ARTISAN" };

    /** Nombre d'entrées conservées par classement. */
    private static final int TOP_SIZE = 10;

    private final OriginsFightCore plugin;
    private final JobDatabase      database;

    /** Cache mémoire : catégorie → liste ordonnée (rang 1..N). Remplacé atomiquement. */
    private volatile Map<String, List<JobDatabase.TopEntry>> snapshot = Collections.emptyMap();
    private volatile long lastUpdate = 0L;

    /** Empêche deux recalculs concurrents. */
    private volatile boolean refreshing = false;
    private int taskId = -1;

    public JobTopManager(OriginsFightCore plugin, JobDatabase database) {
        this.plugin   = plugin;
        this.database = database;
    }

    // ── Cycle de vie ────────────────────────────────────────────────────────────

    /**
     * Charge le snapshot persistant en mémoire, planifie le rafraîchissement
     * automatique toutes les 24 h, puis déclenche un premier recalcul (démarrage).
     */
    public void init() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, List<JobDatabase.TopEntry>> loaded = database.loadSnapshot();
            this.snapshot   = loaded;
            this.lastUpdate = database.loadSnapshotTimestamp();
            LOG.info("[Jobs] Snapshot classement chargé (" + loaded.size() + " catégories).");
            // Recalcul au démarrage pour repartir d'un classement frais.
            doRefresh(null);
        });

        long periodTicks = REFRESH_HOURS * 60L * 60L * 20L;
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> doRefresh(null), periodTicks, periodTicks).getTaskId();
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    // ── Lecture ─────────────────────────────────────────────────────────────────

    /** Renvoie le classement figé de la catégorie demandée (copie, jamais null). */
    public List<JobDatabase.TopEntry> getSnapshot(String jobKey) {
        String key = normalize(jobKey);
        List<JobDatabase.TopEntry> list = snapshot.get(key);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    public long getLastUpdate() { return lastUpdate; }

    // ── Rafraîchissement ──────────────────────────────────────────────────────────

    /**
     * Recalcule le snapshot de façon asynchrone. Si un recalcul est déjà en cours,
     * appelle {@code onDone} immédiatement (sur le thread principal) sans relancer.
     *
     * @param onDone callback exécuté sur le thread principal en fin de recalcul (peut être null)
     */
    public void refreshAsync(Runnable onDone) {
        if (refreshing) {
            if (onDone != null) Bukkit.getScheduler().runTask(plugin, onDone);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doRefresh(onDone));
    }

    /** Recalcul effectif (doit tourner en async : accès DB + résolution de noms). */
    private void doRefresh(Runnable onDone) {
        if (refreshing) {
            if (onDone != null) Bukkit.getScheduler().runTask(plugin, onDone);
            return;
        }
        refreshing = true;
        try {
            Map<String, List<JobDatabase.TopEntry>> fresh = new ConcurrentHashMap<>();
            for (String cat : CATEGORIES) {
                JobType jt = JobType.fromString(cat);
                List<JobDatabase.TopEntry> entries = database.getTop(jt, TOP_SIZE);
                for (JobDatabase.TopEntry e : entries) {
                    try {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(e.uuid));
                        if (op.getName() != null) e.name = op.getName();
                    } catch (Exception ignored) {}
                }
                fresh.put(cat, entries);
            }
            long now = System.currentTimeMillis();
            database.saveSnapshot(fresh, now);
            this.snapshot   = fresh;
            this.lastUpdate = now;
            LOG.info("[Jobs] Classement métiers mis à jour.");
        } catch (Exception ex) {
            LOG.warning("[Jobs] Échec du recalcul du classement : " + ex.getMessage());
        } finally {
            refreshing = false;
            if (onDone != null) Bukkit.getScheduler().runTask(plugin, onDone);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String normalize(String jobKey) {
        if (jobKey == null) return "ALL";
        String up = jobKey.toUpperCase(Locale.ROOT);
        for (String c : CATEGORIES) if (c.equals(up)) return up;
        return "ALL";
    }
}
