package fr.originsfight.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Verrou de présence cross-serveur — fondation du transfert d'inventaire/enderchest entre serveurs.
 *
 * <p>Garantit qu'un joueur n'est considéré « actif » que sur UN seul serveur à la fois. Quand un
 * joueur arrive sur un serveur, on tente d'acquérir le verrou ; s'il est encore détenu (online) par
 * un autre serveur, c'est qu'il n'a pas encore été correctement sauvegardé/libéré ailleurs.
 *
 * <p>Table {@code player_locks(uuid PK, server_id, locked_at, online)}.
 *
 * <p>Ce lot pose la mécanique (acquérir/libérer + détection de conflit) ; le chargement/sauvegarde
 * effectif des items sera branché dessus dans le lot suivant.
 */
public class PlayerLockService {

    private static final Logger LOG = Logger.getLogger("PlayerLock");

    private final Database db;

    public PlayerLockService(Database db) {
        this.db = db;
    }

    public void createTable() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS player_locks (" +
                 "  uuid      VARCHAR(36) PRIMARY KEY," +
                 "  server_id VARCHAR(64) NOT NULL," +
                 "  locked_at BIGINT      NOT NULL," +
                 "  online    BOOLEAN     NOT NULL DEFAULT TRUE" +
                 ")")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.severe("[Lock] createTable: " + e.getMessage());
        }
    }

    /**
     * Tente d'acquérir le verrou pour ce serveur.
     *
     * @return {@code true} si le verrou est acquis (libre, déjà à nous, ou détenu hors-ligne ailleurs) ;
     *         {@code false} si un autre serveur le détient encore en ligne.
     */
    public boolean acquire(UUID uuid, String serverId) {
        long now = System.currentTimeMillis();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                String owner = null;
                boolean online = false;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT server_id, online FROM player_locks WHERE uuid = ? FOR UPDATE")) {
                    sel.setString(1, uuid.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) { owner = rs.getString("server_id"); online = rs.getBoolean("online"); }
                    }
                }

                // Conflit : détenu en ligne par un AUTRE serveur.
                if (owner != null && online && !owner.equals(serverId)) {
                    c.rollback();
                    return false;
                }

                // Libre, à nous, ou détenu hors-ligne ailleurs → (ré)acquisition via upsert.
                try (PreparedStatement up = c.prepareStatement(
                        "MERGE INTO player_locks (uuid, server_id, locked_at, online) " +
                        "KEY(uuid) VALUES (?, ?, ?, TRUE)")) {
                    up.setString(1, uuid.toString());
                    up.setString(2, serverId);
                    up.setLong(3, now);
                    up.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.severe("[Lock] acquire(" + uuid + "): " + e.getMessage());
            // En cas d'erreur base, on n'empêche pas la connexion du joueur.
            return true;
        }
    }

    /**
     * Remet à zéro (online=false) tous les verrous appartenant à ce serveur.
     * À appeler AU DÉMARRAGE : ce serveur étant vide à ce moment, tout verrou à son nom est
     * un fantôme laissé par un crash précédent.
     */
    public void releaseAllForServer(String serverId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE player_locks SET online = FALSE WHERE server_id = ? AND online = TRUE")) {
            ps.setString(1, serverId);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("[Lock] " + n + " verrou(x) fantôme(s) de '" + serverId + "' nettoyé(s) au démarrage.");
        } catch (SQLException e) {
            LOG.severe("[Lock] releaseAllForServer(" + serverId + "): " + e.getMessage());
        }
    }

    /** Libère le verrou (passe {@code online=false}) si détenu par ce serveur. */
    public void release(UUID uuid, String serverId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE player_locks SET online = FALSE WHERE uuid = ? AND server_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.severe("[Lock] release(" + uuid + "): " + e.getMessage());
        }
    }

    /** {@code true} si le joueur est détenu en ligne par un autre serveur. */
    public boolean isLockedElsewhere(UUID uuid, String serverId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM player_locks WHERE uuid = ? AND online = TRUE AND server_id <> ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.severe("[Lock] isLockedElsewhere(" + uuid + "): " + e.getMessage());
            return false;
        }
    }
}
