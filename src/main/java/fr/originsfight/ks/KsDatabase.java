package fr.originsfight.ks;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Base de données SQLite pour les statistiques KS (Kill Score).
 *
 * Table : ks_stats
 *   uuid        TEXT PRIMARY KEY
 *   name        TEXT
 *   kills       INTEGER  -- joueurs tués (PvP)
 *   deaths      INTEGER  -- morts causées par un joueur
 *   playtime_s  INTEGER  -- temps de jeu en secondes
 *   last_join   INTEGER  -- timestamp dernière connexion (ms)
 */
public class KsDatabase {

    private Connection connection;
    private final File dbFile;

    public KsDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "ks.db");
    }

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTable();
            return true;
        } catch (Exception e) {
            Bukkit.getLogger().severe("[KS] Erreur SQLite : " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) {}
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed())
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        return connection;
    }

    private void createTable() throws SQLException {
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS ks_stats (" +
            "  uuid       TEXT PRIMARY KEY," +
            "  name       TEXT NOT NULL," +
            "  kills      INTEGER NOT NULL DEFAULT 0," +
            "  deaths     INTEGER NOT NULL DEFAULT 0," +
            "  playtime_s INTEGER NOT NULL DEFAULT 0," +
            "  last_join  INTEGER NOT NULL DEFAULT 0" +
            ")"
        );
    }

    /** S'assure que le joueur existe dans la base. */
    public void ensurePlayer(Player p) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT OR IGNORE INTO ks_stats (uuid, name, last_join) VALUES (?, ?, ?)");
            ps.setString(1, p.getUniqueId().toString());
            ps.setString(2, p.getName());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate(); ps.close();
            // Mettre à jour le nom au cas où il a changé
            PreparedStatement upd = conn().prepareStatement("UPDATE ks_stats SET name = ? WHERE uuid = ?");
            upd.setString(1, p.getName()); upd.setString(2, p.getUniqueId().toString());
            upd.executeUpdate(); upd.close();
        } catch (SQLException e) { log("ensurePlayer: " + e.getMessage()); }
    }

    public void addKill(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE ks_stats SET kills = kills + 1 WHERE uuid = ?");
            ps.setString(1, uuid.toString()); ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addKill: " + e.getMessage()); }
    }

    public void addDeath(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE ks_stats SET deaths = deaths + 1 WHERE uuid = ?");
            ps.setString(1, uuid.toString()); ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addDeath: " + e.getMessage()); }
    }

    /** Enregistre la connexion (pour calculer le temps de jeu à la déconnexion). */
    public void setJoinTime(UUID uuid, long timestamp) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE ks_stats SET last_join = ? WHERE uuid = ?");
            ps.setLong(1, timestamp); ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("setJoinTime: " + e.getMessage()); }
    }

    /** Ajoute le temps de jeu (en secondes) depuis la dernière connexion. */
    public void addPlaytime(UUID uuid, long seconds) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE ks_stats SET playtime_s = playtime_s + ? WHERE uuid = ?");
            ps.setLong(1, seconds); ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addPlaytime: " + e.getMessage()); }
    }

    public KsStats getStats(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM ks_stats WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new KsStats(
                    rs.getString("name"),
                    rs.getInt("kills"),
                    rs.getInt("deaths"),
                    rs.getLong("playtime_s")
                );
            }
        } catch (SQLException e) { log("getStats: " + e.getMessage()); }
        return null;
    }

    /** Top N joueurs triés par kills décroissants. */
    public java.util.List<KsStats> getTopKs(int limit) {
        java.util.List<KsStats> list = new ArrayList<>();
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM ks_stats ORDER BY kills DESC LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                list.add(new KsStats(rs.getString("name"), rs.getInt("kills"),
                        rs.getInt("deaths"), rs.getLong("playtime_s")));
            ps.close();
        } catch (SQLException e) { log("getTopKs: " + e.getMessage()); }
        return list;
    }

    /** Rang du joueur dans le classement kills (1 = meilleur). Retourne -1 si absent. */
    public int getRank(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM ks_stats WHERE kills > " +
                "(SELECT kills FROM ks_stats WHERE uuid = ?)");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) + 1;
            ps.close();
        } catch (SQLException e) { log("getRank: " + e.getMessage()); }
        return -1;
    }

    private void log(String msg) { Bukkit.getLogger().warning("[KS] " + msg); }

    // ── Data class ────────────────────────────────────────────────────────────

    public static class KsStats {
        public final String name;
        public final int kills, deaths;
        public final long playtimeSeconds;

        public KsStats(String name, int kills, int deaths, long playtimeSeconds) {
            this.name = name; this.kills = kills;
            this.deaths = deaths; this.playtimeSeconds = playtimeSeconds;
        }

        /** Ratio K/D arrondi à 2 décimales. */
        public String ratio() {
            if (deaths == 0) return kills > 0 ? String.valueOf(kills) + ".00" : "0.00";
            return String.format("%.2f", (double) kills / deaths);
        }

        /** Temps de jeu formaté : Xh Xmin. */
        public String formattedPlaytime() {
            long h   = TimeUnit.SECONDS.toHours(playtimeSeconds);
            long min = TimeUnit.SECONDS.toMinutes(playtimeSeconds) % 60;
            if (h > 0) return h + "h " + min + "min";
            if (min > 0) return min + "min";
            return playtimeSeconds + "s";
        }
    }
}

