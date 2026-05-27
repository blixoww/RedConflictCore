package fr.originsfight.friend;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Base de données SQLite pour le système d'amis.
 *
 * Tables :
 *   friends         – relations amicales confirmées (bidirectionnelles)
 *   friend_requests – demandes en attente
 */
public class FriendDatabase {

    private Connection connection;
    private final File dbFile;

    public FriendDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "social/friends.db");
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            dbFile.getParentFile().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            connection.setAutoCommit(true);
            // Mode WAL pour une meilleure fiabilité des écritures
            connection.createStatement().execute("PRAGMA journal_mode=WAL");
            connection.createStatement().execute("PRAGMA synchronous=NORMAL");
            createTables();
            return true;
        } catch (Exception e) {
            Bukkit.getLogger().severe("[Friend] Erreur SQLite : " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) {}
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            connection.setAutoCommit(true);
            connection.createStatement().execute("PRAGMA journal_mode=WAL");
            connection.createStatement().execute("PRAGMA synchronous=NORMAL");
        }
        return connection;
    }

    private void createTables() throws SQLException {
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS friends (" +
            "  uuid_a TEXT NOT NULL," +
            "  uuid_b TEXT NOT NULL," +
            "  PRIMARY KEY (uuid_a, uuid_b)" +
            ")"
        );
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS friend_requests (" +
            "  sender_uuid   TEXT NOT NULL," +
            "  sender_name   TEXT NOT NULL," +
            "  receiver_uuid TEXT NOT NULL," +
            "  receiver_name TEXT NOT NULL," +
            "  sent_at       INTEGER NOT NULL," +
            "  PRIMARY KEY (sender_uuid, receiver_uuid)" +
            ")"
        );
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS player_names (" +
            "  uuid TEXT PRIMARY KEY," +
            "  name TEXT NOT NULL" +
            ")"
        );
    }

    // ── Noms ─────────────────────────────────────────────────────────────────

    public void saveName(UUID uuid, String name) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT OR REPLACE INTO player_names (uuid, name) VALUES (?, ?)");
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("saveName: " + e.getMessage()); }
    }

    public String getName(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name FROM player_names WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            String name = rs.next() ? rs.getString("name") : null;
            rs.close(); ps.close();
            return name;
        } catch (SQLException e) { log("getName: " + e.getMessage()); return null; }
    }

    // ── Amis ─────────────────────────────────────────────────────────────────

    /** Ajoute une relation amicale bidirectionnelle. */
    public void addFriend(UUID a, UUID b) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT OR IGNORE INTO friends (uuid_a, uuid_b) VALUES (?, ?)");
            // Stocker dans les deux sens pour faciliter les requêtes
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ps.executeUpdate();
            ps.setString(1, b.toString()); ps.setString(2, a.toString());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) { log("addFriend: " + e.getMessage()); }
    }

    public void removeFriend(UUID a, UUID b) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM friends WHERE (uuid_a = ? AND uuid_b = ?) OR (uuid_a = ? AND uuid_b = ?)");
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ps.setString(3, b.toString()); ps.setString(4, a.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("removeFriend: " + e.getMessage()); }
    }

    public boolean areFriends(UUID a, UUID b) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT 1 FROM friends WHERE uuid_a = ? AND uuid_b = ?");
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ResultSet rs = ps.executeQuery();
            boolean found = rs.next();
            rs.close(); ps.close();
            return found;
        } catch (SQLException e) { log("areFriends: " + e.getMessage()); return false; }
    }

    /** Retourne la liste des UUID amis d'un joueur. */
    public List<UUID> getFriends(UUID uuid) {
        List<UUID> list = new ArrayList<>();
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT uuid_b FROM friends WHERE uuid_a = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(UUID.fromString(rs.getString("uuid_b")));
            rs.close(); ps.close();
        } catch (SQLException e) { log("getFriends: " + e.getMessage()); }
        return list;
    }

    public int countFriends(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM friends WHERE uuid_a = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            int count = rs.next() ? rs.getInt(1) : 0;
            rs.close(); ps.close();
            return count;
        } catch (SQLException e) { log("countFriends: " + e.getMessage()); return 0; }
    }

    // ── Demandes ─────────────────────────────────────────────────────────────

    public void addRequest(UUID sender, String senderName, UUID receiver, String receiverName) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT OR REPLACE INTO friend_requests (sender_uuid,sender_name,receiver_uuid,receiver_name,sent_at) VALUES (?,?,?,?,?)");
            ps.setString(1, sender.toString());
            ps.setString(2, senderName);
            ps.setString(3, receiver.toString());
            ps.setString(4, receiverName);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addRequest: " + e.getMessage()); }
    }

    public void removeRequest(UUID sender, UUID receiver) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM friend_requests WHERE sender_uuid = ? AND receiver_uuid = ?");
            ps.setString(1, sender.toString()); ps.setString(2, receiver.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("removeRequest: " + e.getMessage()); }
    }

    /** Vérifie si sender a déjà envoyé une demande à receiver. */
    public boolean hasRequest(UUID sender, UUID receiver) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT 1 FROM friend_requests WHERE sender_uuid = ? AND receiver_uuid = ?");
            ps.setString(1, sender.toString()); ps.setString(2, receiver.toString());
            ResultSet rs = ps.executeQuery();
            boolean found = rs.next();
            rs.close(); ps.close();
            return found;
        } catch (SQLException e) { log("hasRequest: " + e.getMessage()); return false; }
    }

    /** Retourne les demandes reçues par un joueur (sender_uuid → sender_name). */
    public Map<UUID, String> getPendingRequests(UUID receiver) {
        Map<UUID, String> map = new LinkedHashMap<>();
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT sender_uuid, sender_name FROM friend_requests WHERE receiver_uuid = ? ORDER BY sent_at");
            ps.setString(1, receiver.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) map.put(UUID.fromString(rs.getString("sender_uuid")), rs.getString("sender_name"));
            rs.close(); ps.close();
        } catch (SQLException e) { log("getPendingRequests: " + e.getMessage()); }
        return map;
    }

    /**
     * Retourne toutes les demandes en attente groupées par receiver_uuid.
     * Utilisé pour initialiser le cache mémoire au démarrage.
     */
    public Map<UUID, Map<UUID, String>> getAllPendingRequests() {
        Map<UUID, Map<UUID, String>> all = new HashMap<>();
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT sender_uuid, sender_name, receiver_uuid FROM friend_requests ORDER BY sent_at");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID sender   = UUID.fromString(rs.getString("sender_uuid"));
                String name   = rs.getString("sender_name");
                UUID receiver = UUID.fromString(rs.getString("receiver_uuid"));
                all.computeIfAbsent(receiver, k -> new LinkedHashMap<>()).put(sender, name);
            }
            rs.close(); ps.close();
        } catch (SQLException e) { log("getAllPendingRequests: " + e.getMessage()); }
        return all;
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static void log(String msg) {
        Bukkit.getLogger().warning("[Friend-DB] " + msg);
    }
}

