package fr.originsfight.data;

import fr.originsfight.OriginsFightCore;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Base de données centralisée SQLite pour toutes les données de profil joueur.
 *
 * Fichier : plugins/OriginsFightCore/players.db
 *
 * Table  : player_profiles
 *   uuid        TEXT PRIMARY KEY
 *   name        TEXT              — pseudo actuel
 *   kills       INTEGER DEFAULT 0 — kills PvP
 *   deaths      INTEGER DEFAULT 0 — morts PvP
 *   playtime_s  INTEGER DEFAULT 0 — temps de jeu cumulé (secondes)
 *   last_join   INTEGER DEFAULT 0 — timestamp de la dernière connexion (ms)
 *   balance     INTEGER DEFAULT 0 — snapshot solde Vault
 *   rank        TEXT DEFAULT ''   — snapshot préfixe rang (Vault Chat)
 *   faction     TEXT DEFAULT ''   — snapshot tag de faction
 *   streak      INTEGER DEFAULT 0 — snapshot killstreak actuel
 *   bounty      INTEGER DEFAULT 0 — snapshot prime active
 *
 * Cette classe remplace KsDatabase et sert de source unique de vérité pour
 * l'affichage du /profil. Les données externes (Vault, Factions) sont
 * synchronisées ici à chaque connexion et lors de la visualisation du profil.
 */
public class PlayerDatabase {

    private Connection connection;
    private final File dbFile;

    public PlayerDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "players.db");
    }

    // ── Initialisation / Fermeture ────────────────────────────────────────────

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTable();
            return true;
        } catch (Exception e) {
            log("Erreur SQLite à l'init : " + e.getMessage());
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
            "CREATE TABLE IF NOT EXISTS player_profiles (" +
            "  uuid        TEXT    PRIMARY KEY," +
            "  name        TEXT    NOT NULL DEFAULT ''," +
            "  kills       INTEGER NOT NULL DEFAULT 0," +
            "  deaths      INTEGER NOT NULL DEFAULT 0," +
            "  playtime_s  INTEGER NOT NULL DEFAULT 0," +
            "  last_join   INTEGER NOT NULL DEFAULT 0," +
            "  balance     INTEGER NOT NULL DEFAULT 0," +
            "  rank        TEXT    NOT NULL DEFAULT 'Joueur'," +
            "  faction     TEXT    NOT NULL DEFAULT ''," +
            "  streak      INTEGER NOT NULL DEFAULT 0," +
            "  bounty      INTEGER NOT NULL DEFAULT 0" +
            ")"
        );
    }

    // ── Écriture — données KS (mises à jour temps réel) ──────────────────────

    /** S'assure que le joueur existe dans la base et met à jour son pseudo. */
    public void ensurePlayer(Player p) {
        try {
            PreparedStatement ins = conn().prepareStatement(
                "INSERT OR IGNORE INTO player_profiles (uuid, name) VALUES (?, ?)");
            ins.setString(1, p.getUniqueId().toString());
            ins.setString(2, p.getName());
            ins.executeUpdate(); ins.close();

            PreparedStatement upd = conn().prepareStatement(
                "UPDATE player_profiles SET name = ? WHERE uuid = ?");
            upd.setString(1, p.getName());
            upd.setString(2, p.getUniqueId().toString());
            upd.executeUpdate(); upd.close();
        } catch (SQLException e) { log("ensurePlayer: " + e.getMessage()); }
    }

    public void addKill(UUID uuid) {
        exec("UPDATE player_profiles SET kills = kills + 1 WHERE uuid = ?", uuid.toString());
    }

    public void addDeath(UUID uuid) {
        exec("UPDATE player_profiles SET deaths = deaths + 1 WHERE uuid = ?", uuid.toString());
    }

    public void addPlaytime(UUID uuid, long seconds) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET playtime_s = playtime_s + ? WHERE uuid = ?");
            ps.setLong(1, seconds);
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addPlaytime: " + e.getMessage()); }
    }

    public void setJoinTime(UUID uuid, long timestamp) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET last_join = ? WHERE uuid = ?");
            ps.setLong(1, timestamp);
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("setJoinTime: " + e.getMessage()); }
    }

    // ── Écriture — snapshots données externes ─────────────────────────────────

    /** Met à jour le solde (snapshot depuis Vault Economy). */
    public void updateBalance(UUID uuid, long balance) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET balance = ? WHERE uuid = ?");
            ps.setLong(1, balance);
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("updateBalance: " + e.getMessage()); }
    }

    /** Met à jour le rang (snapshot depuis Vault Chat). */
    public void updateRank(UUID uuid, String rank) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET rank = ? WHERE uuid = ?");
            ps.setString(1, rank != null ? rank : "Joueur");
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("updateRank: " + e.getMessage()); }
    }

    /** Met à jour la faction (snapshot depuis le plugin Factions). */
    public void updateFaction(UUID uuid, String faction) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET faction = ? WHERE uuid = ?");
            ps.setString(1, faction != null ? faction : "");
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("updateFaction: " + e.getMessage()); }
    }

    /** Met à jour le killstreak (snapshot depuis KillstreakManager). */
    public void setStreak(UUID uuid, int streak) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET streak = ? WHERE uuid = ?");
            ps.setInt(1, streak);
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("setStreak: " + e.getMessage()); }
    }

    /** Met à jour la prime active (snapshot depuis BountyManager). */
    public void setBounty(UUID uuid, long bounty) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "UPDATE player_profiles SET bounty = ? WHERE uuid = ?");
            ps.setLong(1, bounty);
            ps.setString(2, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("setBounty: " + e.getMessage()); }
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    /**
     * Retourne le profil complet d'un joueur (toutes les colonnes).
     * Retourne {@code null} si le joueur n'est pas dans la base.
     */
    public PlayerProfile getProfile(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name, kills, deaths, playtime_s, balance, rank, faction, streak, bounty " +
                "FROM player_profiles WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                PlayerProfile p = new PlayerProfile(
                    uuid,
                    rs.getString("name"),
                    rs.getInt("kills"),
                    rs.getInt("deaths"),
                    rs.getLong("playtime_s"),
                    rs.getLong("balance"),
                    rs.getString("rank"),
                    rs.getString("faction"),
                    rs.getInt("streak"),
                    rs.getLong("bounty")
                );
                rs.close(); ps.close();
                return p;
            }
            rs.close(); ps.close();
        } catch (SQLException e) { log("getProfile: " + e.getMessage()); }
        return null;
    }

    /**
     * Compatibilité avec l'ancien KsDatabase — retourne les stats KS uniquement.
     * Préférer {@link #getProfile(UUID)} pour le /profil.
     */
    public KsStats getStats(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM player_profiles WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                KsStats s = new KsStats(
                    rs.getString("name"),
                    rs.getInt("kills"),
                    rs.getInt("deaths"),
                    rs.getLong("playtime_s")
                );
                rs.close(); ps.close();
                return s;
            }
            rs.close(); ps.close();
        } catch (SQLException e) { log("getStats: " + e.getMessage()); }
        return null;
    }

    /** Top N joueurs par kills décroissants. */
    public List<KsStats> getTopKs(int limit) {
        List<KsStats> list = new ArrayList<>();
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM player_profiles ORDER BY kills DESC LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                list.add(new KsStats(rs.getString("name"), rs.getInt("kills"),
                        rs.getInt("deaths"), rs.getLong("playtime_s")));
            rs.close(); ps.close();
        } catch (SQLException e) { log("getTopKs: " + e.getMessage()); }
        return list;
    }

    /** Rang du joueur dans le classement kills (1 = meilleur). Retourne -1 si absent. */
    public int getRank(UUID uuid) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM player_profiles WHERE kills > " +
                "(SELECT COALESCE((SELECT kills FROM player_profiles WHERE uuid = ?), -1))");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) { int r = rs.getInt(1) + 1; rs.close(); ps.close(); return r; }
            rs.close(); ps.close();
        } catch (SQLException e) { log("getRank: " + e.getMessage()); }
        return -1;
    }

    // ── Helpers internes ─────────────────────────────────────────────────────

    private void exec(String sql, String param) {
        try {
            PreparedStatement ps = conn().prepareStatement(sql);
            ps.setString(1, param);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log(sql.substring(0, Math.min(40, sql.length())) + "… : " + e.getMessage()); }
    }

    private void log(String msg) {
        java.util.logging.Logger.getLogger("Minecraft").warning("[PlayerDB] " + msg);
    }

    // ── Classes de données ───────────────────────────────────────────────────

    /**
     * Profil complet d'un joueur — toutes les données du /profil en un seul objet.
     */
    public static class PlayerProfile {
        public final UUID   uuid;
        public final String name;
        public final int    kills;
        public final int    deaths;
        public final long   playtimeSeconds;
        public final long   balance;
        public final String rank;
        public final String faction;
        public final int    streak;
        public final long   bounty;

        public PlayerProfile(UUID uuid, String name, int kills, int deaths,
                             long playtimeSeconds, long balance,
                             String rank, String faction,
                             int streak, long bounty) {
            this.uuid            = uuid;
            this.name            = name;
            this.kills           = kills;
            this.deaths          = deaths;
            this.playtimeSeconds = playtimeSeconds;
            this.balance         = balance;
            this.rank            = rank != null ? rank : "Joueur";
            this.faction         = faction != null ? faction : "";
            this.streak          = streak;
            this.bounty          = bounty;
        }

        /** Ratio K/D arrondi à 2 décimales. */
        public String ratio() {
            if (deaths == 0) return kills > 0 ? kills + ".00" : "0.00";
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

    /**
     * Stats KS uniquement — conservé pour la compatibilité avec KsCommand.
     */
    public static class KsStats {
        public final String name;
        public final int    kills;
        public final int    deaths;
        public final long   playtimeSeconds;

        public KsStats(String name, int kills, int deaths, long playtimeSeconds) {
            this.name            = name;
            this.kills           = kills;
            this.deaths          = deaths;
            this.playtimeSeconds = playtimeSeconds;
        }

        public String ratio() {
            if (deaths == 0) return kills > 0 ? kills + ".00" : "0.00";
            return String.format("%.2f", (double) kills / deaths);
        }

        public String formattedPlaytime() {
            long h   = TimeUnit.SECONDS.toHours(playtimeSeconds);
            long min = TimeUnit.SECONDS.toMinutes(playtimeSeconds) % 60;
            if (h > 0) return h + "h " + min + "min";
            if (min > 0) return min + "min";
            return playtimeSeconds + "s";
        }
    }
}

