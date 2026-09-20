package fr.redconflict.succes;

import fr.redconflict.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistance H2 des succès, sur le pool partagé du plugin.
 *
 * <pre>
 * player_succes
 *   uuid      VARCHAR(36)   \ clé primaire composite
 *   succes_id VARCHAR(64)   /
 *   progress  INT           avancement courant
 *   unlocked  BOOLEAN       objectif atteint
 *   claimed   BOOLEAN       récompense versée
 * </pre>
 *
 * <p><b>Pourquoi deux drapeaux et pas un.</b> Débloquer et encaisser sont deux
 * moments distincts : on débloque en minant, parfois l'inventaire plein, parfois
 * pendant que le joueur se fait tuer. Verser la récompense à cet instant, c'est
 * la faire tomber au sol. Le déblocage notifie ; la réclamation, déclenchée par
 * le joueur depuis le menu, verse — et {@code claimed} garantit qu'elle ne
 * verse qu'une fois, quoi qu'envoie le client.
 */
public class SuccesDatabase {

    private static final Logger LOG = Logger.getLogger("Succes");

    private final Database db;

    public SuccesDatabase(Database db) {
        this.db = db;
    }

    public boolean connect() {
        try (Connection c = db.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_succes (" +
                "  uuid      VARCHAR(36) NOT NULL," +
                "  succes_id VARCHAR(64) NOT NULL," +
                "  progress  INT     NOT NULL DEFAULT 0," +
                "  unlocked  BOOLEAN NOT NULL DEFAULT FALSE," +
                "  claimed   BOOLEAN NOT NULL DEFAULT FALSE," +
                "  PRIMARY KEY (uuid, succes_id)" +
                ")"
            );
            return true;
        } catch (SQLException e) {
            LOG.severe("[Succes] Erreur H2 : " + e.getMessage());
            return false;
        }
    }

    /** Tout l'avancement d'un joueur, par identifiant de succès. */
    public Map<String, Entry> load(UUID uuid) {
        Map<String, Entry> result = new HashMap<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT succes_id, progress, unlocked, claimed FROM player_succes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("succes_id"), new Entry(
                            rs.getInt("progress"),
                            rs.getBoolean("unlocked"),
                            rs.getBoolean("claimed")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("[Succes] load(" + uuid + ") : " + e.getMessage());
        }
        return result;
    }

    /** Écrit (ou réécrit) une ligne. */
    public void save(UUID uuid, String succesId, Entry entry) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO player_succes (uuid, succes_id, progress, unlocked, claimed) KEY (uuid, succes_id) " +
                "VALUES (?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, succesId);
            ps.setInt(3, entry.progress);
            ps.setBoolean(4, entry.unlocked);
            ps.setBoolean(5, entry.claimed);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[Succes] save(" + uuid + ", " + succesId + ") : " + e.getMessage());
        }
    }

    /**
     * Marque la récompense comme versée, <b>et seulement si elle ne l'était
     * pas</b>.
     *
     * <p>C'est la garantie anti-double-versement, et elle est posée ici et pas
     * dans le cache mémoire : deux paquets de réclamation arrivant coup sur
     * coup passeraient tous les deux le test en mémoire, mais un seul verra
     * cette mise à jour toucher une ligne.
     *
     * @return vrai si c'est bien cet appel qui a fait passer le drapeau
     */
    public boolean markClaimed(UUID uuid, String succesId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_succes SET claimed = TRUE " +
                "WHERE uuid = ? AND succes_id = ? AND unlocked = TRUE AND claimed = FALSE")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, succesId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOG.warning("[Succes] markClaimed(" + uuid + ", " + succesId + ") : " + e.getMessage());
            return false;
        }
    }

    /**
     * Ajoute à l'avancement d'un joueur <b>hors ligne</b>, sans passer par le
     * cache. Sert aux événements qui touchent un absent — une annonce vendue à
     * l'hôtel des ventes pendant qu'il dort.
     *
     * @return l'avancement après ajout, ou -1 si l'écriture a échoué
     */
    public int addOffline(UUID uuid, String succesId, int amount) {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO player_succes (uuid, succes_id, progress, unlocked, claimed) KEY (uuid, succes_id) " +
                    "SELECT ?, ?, COALESCE(MAX(progress), 0) + ?, COALESCE(MAX(unlocked), FALSE), " +
                    "       COALESCE(MAX(claimed), FALSE) " +
                    "FROM player_succes WHERE uuid = ? AND succes_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, succesId);
                ps.setInt(3, amount);
                ps.setString(4, uuid.toString());
                ps.setString(5, succesId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT progress FROM player_succes WHERE uuid = ? AND succes_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, succesId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        } catch (SQLException e) {
            LOG.warning("[Succes] addOffline(" + uuid + ", " + succesId + ") : " + e.getMessage());
            return -1;
        }
    }

    /** Pose le déblocage d'un joueur hors ligne ; la récompense reste à réclamer. */
    public void markUnlockedOffline(UUID uuid, String succesId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_succes SET unlocked = TRUE WHERE uuid = ? AND succes_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, succesId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[Succes] markUnlockedOffline(" + uuid + ", " + succesId + ") : " + e.getMessage());
        }
    }

    /**
     * Efface l'avancement d'un joueur sur <b>un seul</b> succès (commande
     * d'administration).
     *
     * <p>Utile quand un objectif change de barème : plutôt que de tout remettre
     * à zéro, on ne rouvre que celui qui n'a plus le même sens. La ligne est
     * supprimée et non remise à zéro — l'absence de ligne <i>est</i> l'état
     * neutre, et c'est ce que {@code entryOf} recrée à la demande.
     */
    public void reset(UUID uuid, String succesId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM player_succes WHERE uuid = ? AND succes_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, succesId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[Succes] reset(" + uuid + ", " + succesId + ") : " + e.getMessage());
        }
    }

    /** Efface tout l'avancement d'un joueur (commande d'administration). */
    public void reset(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM player_succes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[Succes] reset(" + uuid + ") : " + e.getMessage());
        }
    }

    /** No-op : le pool est fermé centralement. */
    public void disconnect() {
    }

    /** Avancement d'un joueur sur un succès. Muté en place dans le cache. */
    public static final class Entry {
        public int progress;
        public boolean unlocked;
        public boolean claimed;

        public Entry() {
            this(0, false, false);
        }

        public Entry(int progress, boolean unlocked, boolean claimed) {
            this.progress = progress;
            this.unlocked = unlocked;
            this.claimed = claimed;
        }

        /** 0 verrouillé · 1 débloqué, récompense en attente · 2 réclamé. */
        public int state() {
            if (!unlocked) return 0;
            return claimed ? 2 : 1;
        }
    }
}
