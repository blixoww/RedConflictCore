package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.db.Database;
import fr.redconflict.staff.HwidBanService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Miroir des empreintes matérielles <b>des comptes bannis</b> vers la base du
 * site, pour que le launcher puisse refuser le lancement avant même que le jeu
 * démarre.
 *
 * <p><b>Pourquoi un miroir plutôt qu'un appel au serveur de jeu.</b> Le launcher
 * ne parle qu'au site : les serveurs de jeu n'exposent aucune API, et leur en
 * ouvrir une (avec un port, une authentification et une surface d'attaque de
 * plus) coûterait bien plus cher que de recopier quelques centaines de hachages
 * dans une table que le site sait déjà lire. Le miroir suit exactement le modèle
 * de {@link SiteSync} : sens unique, H2 propriétaire, MariaDB en lecture.
 *
 * <p><b>Ce qui traverse.</b> Uniquement les lignes {@code player_hwid} des
 * comptes sous ban actif — pas l'empreinte de toute la base de joueurs. Ce sont
 * des SHA-256 calculés côté client : aucun numéro de série ne quitte la machine
 * du joueur, ni le serveur de jeu.
 *
 * <p><b>La politique voyage avec les données.</b> Seuil, refus des VM et
 * interrupteur général sont recopiés dans {@code rc_hwid_policy} à chaque
 * passage. Le site ne décide de rien : il applique ce que
 * {@code anticheat.ban.hwid.*} dit en jeu. Sans ça, le launcher et le serveur
 * pourraient diverger — le premier laissant passer ce que le second kicke, ou
 * l'inverse, ce qui est encore plus pénible à diagnostiquer.
 *
 * <p><b>Le launcher n'est pas le verrou.</b> Il évite au tricheur de télécharger
 * le client pour se faire kicker trois secondes après. Le vrai refus reste celui
 * du serveur, à la connexion, où rien n'est cru sur parole.
 */
public final class HwidMirror {

    /** Au-delà, on découpe l'envoi : un batch géant tient la connexion trop longtemps. */
    private static final int BATCH_SIZE = 500;

    private final RedConflictCore plugin;
    private final Database h2;
    private final SiteDatabase site;

    /** Le schéma est-il en place ? Retesté à chaque passage tant qu'il ne l'est pas. */
    private boolean schemaReady;

    public HwidMirror(RedConflictCore plugin, Database h2, SiteDatabase site) {
        this.plugin = plugin;
        this.h2 = h2;
        this.site = site;
    }

    // ── Schéma ─────────────────────────────────────────────────────────────────

    private static final String CREATE_BANS =
            "CREATE TABLE IF NOT EXISTS rc_hwid_bans ("
          + "  hash       CHAR(64)     NOT NULL,"
          + "  type       VARCHAR(24)  NOT NULL,"
          + "  weight     INT          NOT NULL DEFAULT 1,"
          + "  uuid       CHAR(36)     NOT NULL,"
          + "  name       VARCHAR(32)  NOT NULL DEFAULT '',"
          + "  reason     VARCHAR(191) NOT NULL DEFAULT '',"
          + "  expires_at BIGINT       NULL DEFAULT NULL,"
          + "  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP"
          + "             ON UPDATE CURRENT_TIMESTAMP,"
          + "  PRIMARY KEY (uuid, type, hash),"
          + "  KEY idx_rc_hwid_bans_hash (hash)"
          + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String CREATE_POLICY =
            "CREATE TABLE IF NOT EXISTS rc_hwid_policy ("
          + "  id         TINYINT    NOT NULL PRIMARY KEY,"
          + "  enabled    TINYINT(1) NOT NULL DEFAULT 0,"
          + "  block_vms  TINYINT(1) NOT NULL DEFAULT 1,"
          + "  threshold  INT        NOT NULL DEFAULT 4,"
          + "  updated_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP"
          + "             ON UPDATE CURRENT_TIMESTAMP"
          + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    /**
     * Vérifie la présence des deux tables et, à défaut, tente de les créer.
     *
     * <p>Comme pour les tables du miroir des profils, l'échec n'est pas une
     * anomalie : le compte {@code rc_sync} n'a volontairement pas le droit
     * {@code CREATE} sur une base qui contient aussi {@code users}. On affiche
     * alors le fichier à passer à la main, et le reste du pont continue.
     */
    private boolean ensureTables() {
        if (schemaReady) return true;
        if (tablesExist()) {
            schemaReady = true;
            return true;
        }

        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(CREATE_BANS);
            st.executeUpdate(CREATE_POLICY);
        } catch (SQLException ignored) {
            // Droit CREATE absent : c'est le réglage recommandé, on le dit plus bas.
        }

        schemaReady = tablesExist();
        if (!schemaReady) {
            plugin.getLogger().warning("[HWID] Tables du miroir absentes : le launcher ne pourra pas "
                    + "pré-filtrer (le serveur, lui, refuse toujours à la connexion). Passe "
                    + "sql/005-hwid-launcher.sql avec un compte administrateur.");
        }
        return schemaReady;
    }

    private boolean tablesExist() {
        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeQuery("SELECT 1 FROM rc_hwid_bans LIMIT 1").close();
            st.executeQuery("SELECT 1 FROM rc_hwid_policy LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Synchronisation ────────────────────────────────────────────────────────

    /**
     * Recopie la politique puis les empreintes des bannis. À n'appeler que
     * depuis un thread asynchrone, comme le reste du miroir.
     *
     * @return le nombre de lignes publiées, ou -1 si le miroir n'a rien pu faire
     */
    public int sync() {
        if (!ensureTables()) return -1;

        HwidBanService service = plugin.getHwidBanService();
        // StaffPlugin absent (ou pas encore démarré) : on publie une politique
        // désactivée plutôt que rien. Un launcher qui lit une table vide ne sait
        // pas s'il doit bloquer ou laisser passer ; une politique explicite, si.
        boolean enabled  = service != null && service.isEnabled();
        boolean blockVms = service == null || service.isBlockVms();
        int threshold    = service == null ? 4 : service.getThreshold();

        try {
            writePolicy(enabled, blockVms, threshold);

            // Module coupé : on vide le miroir. Garder des hachages que plus
            // personne n'applique, c'est conserver une donnée personnelle sans
            // raison — et risquer qu'elle resserve un jour sans qu'on l'ait voulu.
            if (!enabled) {
                clearBans();
                return 0;
            }
            return writeBans();
        } catch (SQLException e) {
            plugin.getLogger().warning("[HWID] Miroir vers le site en échec : " + e.getMessage());
            return -1;
        }
    }

    private void writePolicy(boolean enabled, boolean blockVms, int threshold) throws SQLException {
        String sql = "INSERT INTO rc_hwid_policy (id, enabled, block_vms, threshold) "
                   + "VALUES (1, ?, ?, ?) "
                   + "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), "
                   + "  block_vms=VALUES(block_vms), threshold=VALUES(threshold)";
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setInt(2, blockVms ? 1 : 0);
            ps.setInt(3, threshold);
            ps.executeUpdate();
        }
    }

    private void clearBans() throws SQLException {
        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM rc_hwid_bans");
        }
    }

    /**
     * Republie l'intégralité des empreintes sous ban actif.
     *
     * <p>Vidage et remplissage dans une même transaction : sans elle, un
     * launcher qui interroge entre les deux verrait une table vide et laisserait
     * passer un banni. Même raison que pour {@code rc_factions}, avec une
     * conséquence plus fâcheuse qu'un classement momentanément vide.
     *
     * <p>Republier tout à chaque passage plutôt que tenir un différentiel : le
     * volume est celui des comptes bannis (des centaines de lignes, pas des
     * millions), et un différentiel qui se désynchronise laisserait un banni
     * jouer sans que personne ne comprenne pourquoi.
     */
    private int writeBans() throws SQLException {
        Map<String, Ban> bans = activeBans();
        if (bans.isEmpty()) {
            clearBans();
            return 0;
        }

        List<Row> rows = hwidRows(bans);

        String write = "INSERT INTO rc_hwid_bans (hash, type, weight, uuid, name, reason, expires_at) "
                     + "VALUES (?,?,?,?,?,?,?)";

        try (Connection dst = site.getConnection()) {
            boolean autoCommit = dst.getAutoCommit();
            dst.setAutoCommit(false);
            try (Statement clear = dst.createStatement();
                 PreparedStatement ws = dst.prepareStatement(write)) {

                // DELETE et non TRUNCATE : TRUNCATE exige le droit DROP, que le
                // compte du plugin n'a volontairement pas, et il valide
                // implicitement la transaction.
                clear.executeUpdate("DELETE FROM rc_hwid_bans");

                int pending = 0;
                for (Row r : rows) {
                    ws.setString(1, r.hash);
                    ws.setString(2, r.type);
                    ws.setInt(3, r.weight);
                    ws.setString(4, r.uuid);
                    ws.setString(5, r.name);
                    ws.setString(6, r.reason);
                    if (r.expiresAt == null) ws.setNull(7, java.sql.Types.BIGINT);
                    else ws.setLong(7, r.expiresAt.longValue());
                    ws.addBatch();

                    if (++pending >= BATCH_SIZE) {
                        ws.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) ws.executeBatch();
                dst.commit();
            } catch (SQLException e) {
                dst.rollback();
                throw e;
            } finally {
                dst.setAutoCommit(autoCommit);
            }
        }
        return rows.size();
    }

    // ── Lecture H2 ─────────────────────────────────────────────────────────────

    /**
     * Bans actifs, un par compte : le plus récemment prononcé gagne.
     *
     * <p>Un compte peut porter plusieurs lignes {@code sanctions} de type BAN
     * (un temporaire, puis un définitif). Le launcher n'affiche qu'un motif :
     * autant que ce soit le dernier, celui que le joueur reconnaîtra.
     */
    private Map<String, Ban> activeBans() throws SQLException {
        Map<String, Ban> out = new HashMap<String, Ban>();
        String sql = "SELECT uuid, reason, expires_at FROM sanctions "
                   + "WHERE type = 'BAN' AND active = 1 "
                   + "  AND (expires_at IS NULL OR expires_at > ?) "
                   + "ORDER BY issued_at ASC";
        try (Connection c = h2.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long expires = r.getLong("expires_at");
                    Long expiresAt = r.wasNull() ? null : Long.valueOf(expires);
                    // Tri croissant : pour un même uuid, la dernière écriture
                    // dans la carte est la sanction la plus récente.
                    out.put(r.getString("uuid"), new Ban(trim(r.getString("reason"), 191), expiresAt));
                }
            }
        }
        return out;
    }

    /** Les lignes {@code player_hwid} des comptes fournis. */
    private List<Row> hwidRows(Map<String, Ban> bans) throws SQLException {
        List<Row> rows = new ArrayList<Row>();
        String sql = "SELECT uuid, name, type, weight, hash FROM player_hwid WHERE uuid = ?";
        try (Connection c = h2.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, Ban> e : bans.entrySet()) {
                ps.setString(1, e.getKey());
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        Row row = new Row();
                        row.uuid = r.getString("uuid");
                        row.name = trim(r.getString("name"), 32);
                        row.type = r.getString("type");
                        row.weight = r.getInt("weight");
                        row.hash = r.getString("hash");
                        row.reason = e.getValue().reason;
                        row.expiresAt = e.getValue().expiresAt;
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Ban {
        final String reason;
        final Long expiresAt;
        Ban(String reason, Long expiresAt) { this.reason = reason; this.expiresAt = expiresAt; }
    }

    private static final class Row {
        String hash;
        String type;
        int weight;
        String uuid;
        String name;
        String reason;
        Long expiresAt;
    }
}
