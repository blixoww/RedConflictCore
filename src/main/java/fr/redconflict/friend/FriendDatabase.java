package fr.redconflict.friend;

import fr.redconflict.db.Database;
import org.bukkit.Bukkit;

import java.sql.*;
import java.util.*;

/**
 * Accès H2 (pool partagé) pour le système d'amis.
 *
 * Tables :
 *   friends         – relations amicales confirmées (bidirectionnelles)
 *   friend_requests – demandes en attente
 *   player_names    – cache UUID → pseudo
 */
public class FriendDatabase {

    private final Database db;

    public FriendDatabase(Database db) {
        this.db = db;
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    public boolean init() {
        try (Connection c = db.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS friends (" +
                "  uuid_a VARCHAR(36) NOT NULL," +
                "  uuid_b VARCHAR(36) NOT NULL," +
                "  PRIMARY KEY (uuid_a, uuid_b)" +
                ")"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS friend_requests (" +
                "  sender_uuid   VARCHAR(36) NOT NULL," +
                "  sender_name   VARCHAR(32) NOT NULL," +
                "  receiver_uuid VARCHAR(36) NOT NULL," +
                "  receiver_name VARCHAR(32) NOT NULL," +
                "  sent_at       BIGINT NOT NULL," +
                "  PRIMARY KEY (sender_uuid, receiver_uuid)" +
                ")"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_names (" +
                "  uuid VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(32) NOT NULL" +
                ")"
            );
            return true;
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Friend] Erreur H2 : " + e.getMessage());
            return false;
        }
    }

    /** No-op : le pool est fermé centralement. */
    public void close() { }

    // ── Noms ─────────────────────────────────────────────────────────────────

    public void saveName(UUID uuid, String name) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO player_names (uuid, name) KEY(uuid) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) { log("saveName: " + e.getMessage()); }
    }

    public String getName(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM player_names WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : null;
            }
        } catch (SQLException e) { log("getName: " + e.getMessage()); return null; }
    }

    // ── Amis ─────────────────────────────────────────────────────────────────

    /** Ajoute une relation amicale bidirectionnelle. */
    public void addFriend(UUID a, UUID b) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO friends (uuid_a, uuid_b) KEY(uuid_a, uuid_b) VALUES (?, ?)")) {
            // Stocker dans les deux sens pour faciliter les requêtes
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ps.executeUpdate();
            ps.setString(1, b.toString()); ps.setString(2, a.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("addFriend: " + e.getMessage()); }
    }

    public void removeFriend(UUID a, UUID b) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM friends WHERE (uuid_a = ? AND uuid_b = ?) OR (uuid_a = ? AND uuid_b = ?)")) {
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ps.setString(3, b.toString()); ps.setString(4, a.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("removeFriend: " + e.getMessage()); }
    }

    public boolean areFriends(UUID a, UUID b) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM friends WHERE uuid_a = ? AND uuid_b = ?")) {
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) { log("areFriends: " + e.getMessage()); return false; }
    }

    /** Retourne la liste des UUID amis d'un joueur. */
    public List<UUID> getFriends(UUID uuid) {
        List<UUID> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT uuid_b FROM friends WHERE uuid_a = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(UUID.fromString(rs.getString("uuid_b")));
            }
        } catch (SQLException e) { log("getFriends: " + e.getMessage()); }
        return list;
    }

    public int countFriends(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM friends WHERE uuid_a = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) { log("countFriends: " + e.getMessage()); return 0; }
    }

    // ── Demandes ─────────────────────────────────────────────────────────────

    public void addRequest(UUID sender, String senderName, UUID receiver, String receiverName) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO friend_requests (sender_uuid,sender_name,receiver_uuid,receiver_name,sent_at) " +
                "KEY(sender_uuid, receiver_uuid) VALUES (?,?,?,?,?)")) {
            ps.setString(1, sender.toString());
            ps.setString(2, senderName);
            ps.setString(3, receiver.toString());
            ps.setString(4, receiverName);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { log("addRequest: " + e.getMessage()); }
    }

    public void removeRequest(UUID sender, UUID receiver) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM friend_requests WHERE sender_uuid = ? AND receiver_uuid = ?")) {
            ps.setString(1, sender.toString()); ps.setString(2, receiver.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("removeRequest: " + e.getMessage()); }
    }

    /** Vérifie si sender a déjà envoyé une demande à receiver. */
    public boolean hasRequest(UUID sender, UUID receiver) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM friend_requests WHERE sender_uuid = ? AND receiver_uuid = ?")) {
            ps.setString(1, sender.toString()); ps.setString(2, receiver.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) { log("hasRequest: " + e.getMessage()); return false; }
    }

    /** Retourne les demandes reçues par un joueur (sender_uuid → sender_name). */
    public Map<UUID, String> getPendingRequests(UUID receiver) {
        Map<UUID, String> map = new LinkedHashMap<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT sender_uuid, sender_name FROM friend_requests WHERE receiver_uuid = ? ORDER BY sent_at")) {
            ps.setString(1, receiver.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) map.put(UUID.fromString(rs.getString("sender_uuid")), rs.getString("sender_name"));
            }
        } catch (SQLException e) { log("getPendingRequests: " + e.getMessage()); }
        return map;
    }

    /**
     * Retourne toutes les demandes en attente groupées par receiver_uuid.
     * Utilisé pour initialiser le cache mémoire au démarrage.
     */
    public Map<UUID, Map<UUID, String>> getAllPendingRequests() {
        Map<UUID, Map<UUID, String>> all = new HashMap<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT sender_uuid, sender_name, receiver_uuid FROM friend_requests ORDER BY sent_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID sender   = UUID.fromString(rs.getString("sender_uuid"));
                String name   = rs.getString("sender_name");
                UUID receiver = UUID.fromString(rs.getString("receiver_uuid"));
                all.computeIfAbsent(receiver, k -> new LinkedHashMap<>()).put(sender, name);
            }
        } catch (SQLException e) { log("getAllPendingRequests: " + e.getMessage()); }
        return all;
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static void log(String msg) {
        Bukkit.getLogger().warning("[Friend-DB] " + msg);
    }
}
