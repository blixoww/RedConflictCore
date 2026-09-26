package fr.redconflict.job;

import fr.redconflict.db.Database;
import org.bukkit.block.Block;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Minerais posés à la main, qui ne doivent rien rapporter au métier Mineur.
 *
 * <p><b>L'exploit.</b> Avec Silk Touch, un minerai cassé redonne le bloc
 * lui-même : poser, casser, ramasser, recommencer — un seul diamant montait le
 * métier à l'infini. Même parade que {@link JobFarmerListener} pour la canne à
 * sucre : un bloc posé par un joueur est marqué, et le casser ne paie pas.
 *
 * <p><b>Pourquoi en base, et pas seulement en mémoire comme l'Agriculteur.</b>
 * Un suivi en mémoire s'oublie au redémarrage : il suffirait de stocker des
 * centaines de minerais Silk Touch, de les poser, et d'attendre le reboot
 * quotidien pour tout recasser avec XP. La table {@code job_placed_ores} survit
 * au redémarrage ; la copie en mémoire ne sert qu'à répondre sans requête sur le
 * chemin chaud du {@code BlockBreakEvent}.
 *
 * <p><b>Scopé par {@code server-id}.</b> Faction et Minage partagent la même base
 * H2 et leurs mondes peuvent porter le même nom : sans le serveur dans la clé, un
 * minerai posé sur l'un marquerait la même case sur l'autre.
 *
 * <p>Les écritures passent par <b>un seul thread</b>, dans l'ordre d'arrivée :
 * poser puis casser au même endroit doit donner INSERT puis DELETE, jamais
 * l'inverse — ce que deux tâches asynchrones Bukkit ne garantissent pas.
 */
public final class PlacedOreTracker {

    private static final Logger LOG = Logger.getLogger("Jobs");

    private final Database db;
    private final String serverId;
    /** Table de suivi (constante du code, jamais une saisie : concaténée dans le SQL). */
    private final String table;
    private final String label;

    /** Copie mémoire de la table pour ce serveur. Lue et écrite sur le thread principal. */
    private final Set<String> placed = new HashSet<String>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RedConflict-PlacedOres");
        t.setDaemon(true);
        return t;
    });

    public PlacedOreTracker(Database db) {
        this(db, "job_placed_ores", "minerai(s) posé(s) à la main suivis (sans XP Mineur)");
    }

    /**
     * Même suivi sur une autre table. Les succès « miner N blocs » ont le même
     * besoin que le métier Mineur, mais sur leurs propres matériaux (pierre,
     * obsidienne…) — et une casse ne doit pas effacer la marque de l'autre.
     */
    public PlacedOreTracker(Database db, String table, String label) {
        this.db = db;
        this.serverId = db.getServerId();
        this.table = table;
        this.label = label;
    }

    /** Crée la table si besoin et charge les emplacements de ce serveur. */
    public void init() {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + table + " (" +
                "  server    VARCHAR(32) NOT NULL," +
                "  world     VARCHAR(64) NOT NULL," +
                "  x         INT NOT NULL," +
                "  y         INT NOT NULL," +
                "  z         INT NOT NULL," +
                "  placed_at BIGINT NOT NULL," +
                "  PRIMARY KEY (server, world, x, y, z)" +
                ")"
            );
        } catch (SQLException e) {
            LOG.warning("[Jobs] Table " + table + " : " + e.getMessage());
            return;
        }

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, x, y, z FROM " + table + " WHERE server = ?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    placed.add(key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                }
            }
            LOG.info("[Jobs] " + placed.size() + " " + label + ".");
        } catch (SQLException e) {
            LOG.warning("[Jobs] Chargement de " + table + " : " + e.getMessage());
        }
    }

    /** Termine les écritures en attente. Appelé à l'arrêt du module. */
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── API (thread principal) ────────────────────────────────────────────────

    /** Marque un minerai posé par un joueur. */
    public void mark(Block b) {
        if (placed.add(key(b))) {
            final String world = b.getWorld().getName();
            final int x = b.getX(), y = b.getY(), z = b.getZ();
            submit("MERGE INTO " + table + " (server, world, x, y, z, placed_at) "
                 + "KEY (server, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)",
                   world, x, y, z, System.currentTimeMillis());
        }
    }

    /**
     * Le bloc à cet emplacement a-t-il été posé à la main ? Retire la marque dans
     * tous les cas où elle existait : le bloc quitte l'emplacement.
     */
    public boolean consume(Block b) {
        if (!placed.remove(key(b))) return false;
        submit("DELETE FROM " + table + " WHERE server = ? AND world = ? AND x = ? AND y = ? AND z = ?",
               b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), null);
        return true;
    }

    /** Suit un minerai marqué déplacé par un piston. */
    public void move(Block from, Block to) {
        if (consume(from)) mark(to);
    }

    // ── Interne ───────────────────────────────────────────────────────────────

    private void submit(final String sql, final String world, final int x, final int y, final int z,
                        final Long placedAt) {
        writer.execute(() -> {
            try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, serverId);
                ps.setString(2, world);
                ps.setInt(3, x);
                ps.setInt(4, y);
                ps.setInt(5, z);
                if (placedAt != null) ps.setLong(6, placedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warning("[Jobs] " + table + " : " + e.getMessage());
            }
        });
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }
}
