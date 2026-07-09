package fr.redconflict.data;

import fr.redconflict.db.Database;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Base de données centralisée (H2) pour toutes les données de profil joueur.
 *
 * Table  : player_profiles
 *   uuid        VARCHAR PRIMARY KEY
 *   name        VARCHAR           — pseudo actuel
 *   kills       BIGINT  DEFAULT 0 — kills PvP
 *   deaths      BIGINT  DEFAULT 0 — morts PvP
 *   playtime_s  BIGINT  DEFAULT 0 — temps de jeu cumulé (secondes)
 *   last_join   BIGINT  DEFAULT 0 — timestamp de la dernière connexion (ms)
 *   balance     BIGINT  DEFAULT 0 — snapshot solde Vault
 *   rank_label  VARCHAR DEFAULT 'Joueur' — snapshot préfixe rang (Vault Chat)
 *   faction     VARCHAR DEFAULT ''   — snapshot tag de faction
 *   streak      INT     DEFAULT 0 — snapshot killstreak actuel
 *   bounty      BIGINT  DEFAULT 0 — snapshot prime active
 *   pb          BIGINT  DEFAULT 0 — Points Boutique
 *   xp_boost_until BIGINT DEFAULT 0 — fin du boost x2 (ms)
 *
 * Source unique de vérité pour le /profil. Les données externes (Vault, Factions) y sont
 * synchronisées à chaque connexion et lors de la visualisation du profil.
 *
 * Note : la colonne "rank" est un mot réservé SQL → stockée sous {@code rank_label}.
 */
public class PlayerDatabase {

    private final Database db;

    public PlayerDatabase(Database db) {
        this.db = db;
    }

    // ── Initialisation / Fermeture ────────────────────────────────────────────

    public boolean init() {
        try (Connection c = db.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_profiles (" +
                "  uuid        VARCHAR(36) PRIMARY KEY," +
                "  name        VARCHAR(32) NOT NULL DEFAULT ''," +
                "  kills       BIGINT  NOT NULL DEFAULT 0," +
                "  deaths      BIGINT  NOT NULL DEFAULT 0," +
                "  playtime_s  BIGINT  NOT NULL DEFAULT 0," +
                "  last_join   BIGINT  NOT NULL DEFAULT 0," +
                "  balance     BIGINT  NOT NULL DEFAULT 0," +
                "  rank_label  VARCHAR(64) NOT NULL DEFAULT 'Joueur'," +
                "  faction     VARCHAR(64) NOT NULL DEFAULT ''," +
                "  streak      INT     NOT NULL DEFAULT 0," +
                "  bounty      BIGINT  NOT NULL DEFAULT 0," +
                "  pb          BIGINT  NOT NULL DEFAULT 0," +
                "  xp_boost_until BIGINT NOT NULL DEFAULT 0" +
                ")"
            );
            return true;
        } catch (SQLException e) {
            log("Erreur H2 à l'init : " + e.getMessage());
            return false;
        }
    }

    /** No-op : le pool est fermé centralement par {@link Database#close()}. */
    public void close() { }

    // ── Boost d'XP (x2 métiers, item xp_booster) ──────────────────────────────

    /** Timestamp (ms) de fin du boost x2. 0 si aucun boost. */
    public long getXpBoostUntil(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT xp_boost_until FROM player_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("xp_boost_until") : 0L;
            }
        } catch (SQLException e) { log("getXpBoostUntil: " + e.getMessage()); return 0L; }
    }

    /** Définit le timestamp (ms) de fin du boost x2. */
    public void setXpBoostUntil(UUID uuid, long until) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET xp_boost_until = ? WHERE uuid = ?")) {
            ps.setLong(1, Math.max(0L, until));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("setXpBoostUntil: " + e.getMessage()); }
    }

    // ── Points Boutique (PB) ──────────────────────────────────────────────────

    /** Solde PB d'un joueur. 0 si absent. */
    public int getPB(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT pb FROM player_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("pb") : 0;
            }
        } catch (SQLException e) { log("getPB: " + e.getMessage()); return 0; }
    }

    /** S'assure que la ligne existe (pour les joueurs offline manipulés via /pb). */
    public void ensurePlayer(UUID uuid, String name) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player_profiles (uuid, name) SELECT ?, ? " +
                "WHERE NOT EXISTS (SELECT 1 FROM player_profiles WHERE uuid = ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name != null ? name : "");
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("ensurePlayer(uuid): " + e.getMessage()); }
    }

    /** Définit le solde PB. Garantit >= 0. */
    public void setPB(UUID uuid, int value) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET pb = ? WHERE uuid = ?")) {
            ps.setInt(1, Math.max(0, value));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("setPB: " + e.getMessage()); }
    }

    /** Ajoute des PB. Atomique. */
    public boolean addPB(UUID uuid, int amount) {
        if (amount <= 0) return false;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET pb = pb + ? WHERE uuid = ?")) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { log("addPB: " + e.getMessage()); return false; }
    }

    /** Retire des PB seulement si le solde le permet. Atomique (transaction sur une connexion). */
    public boolean removePB(UUID uuid, int amount) {
        if (amount <= 0) return false;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                int current = 0;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT pb FROM player_profiles WHERE uuid = ? FOR UPDATE")) {
                    sel.setString(1, uuid.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        current = rs.next() ? rs.getInt("pb") : 0;
                    }
                }
                if (current < amount) { c.rollback(); return false; }

                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE player_profiles SET pb = pb - ? WHERE uuid = ?")) {
                    upd.setInt(1, amount);
                    upd.setString(2, uuid.toString());
                    upd.executeUpdate();
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
            log("removePB: " + e.getMessage());
            return false;
        }
    }

    /** UUID d'un joueur connu de la base via son pseudo (case-insensitive). */
    public UUID getUUIDByName(String name) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT uuid FROM player_profiles WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { try { return UUID.fromString(rs.getString("uuid")); } catch (Exception ignored) {} }
            }
        } catch (SQLException e) { log("getUUIDByName: " + e.getMessage()); }
        return null;
    }

    // ── Écriture — données KS (mises à jour temps réel) ──────────────────────

    /** S'assure que le joueur existe dans la base et met à jour son pseudo. */
    public void ensurePlayer(Player p) {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO player_profiles (uuid, name) SELECT ?, ? " +
                    "WHERE NOT EXISTS (SELECT 1 FROM player_profiles WHERE uuid = ?)")) {
                ins.setString(1, p.getUniqueId().toString());
                ins.setString(2, p.getName());
                ins.setString(3, p.getUniqueId().toString());
                ins.executeUpdate();
            }
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE player_profiles SET name = ? WHERE uuid = ?")) {
                upd.setString(1, p.getName());
                upd.setString(2, p.getUniqueId().toString());
                upd.executeUpdate();
            }
        } catch (SQLException e) { log("ensurePlayer: " + e.getMessage()); }
    }

    public void addKill(UUID uuid) {
        exec("UPDATE player_profiles SET kills = kills + 1 WHERE uuid = ?", uuid.toString());
    }

    public void addDeath(UUID uuid) {
        exec("UPDATE player_profiles SET deaths = deaths + 1 WHERE uuid = ?", uuid.toString());
    }

    public void addPlaytime(UUID uuid, long seconds) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET playtime_s = playtime_s + ? WHERE uuid = ?")) {
            ps.setLong(1, seconds);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("addPlaytime: " + e.getMessage()); }
    }

    public void setJoinTime(UUID uuid, long timestamp) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET last_join = ? WHERE uuid = ?")) {
            ps.setLong(1, timestamp);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("setJoinTime: " + e.getMessage()); }
    }

    // ── Écriture — snapshots données externes ─────────────────────────────────

    /** Met à jour le solde (snapshot depuis Vault Economy). */
    public void updateBalance(UUID uuid, long balance) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET balance = ? WHERE uuid = ?")) {
            ps.setLong(1, balance);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("updateBalance: " + e.getMessage()); }
    }

    /** Met à jour le rang (snapshot depuis Vault Chat). */
    public void updateRank(UUID uuid, String rank) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET rank_label = ? WHERE uuid = ?")) {
            ps.setString(1, rank != null ? rank : "Joueur");
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("updateRank: " + e.getMessage()); }
    }

    /** Met à jour la faction (snapshot depuis le plugin Factions). */
    public void updateFaction(UUID uuid, String faction) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET faction = ? WHERE uuid = ?")) {
            ps.setString(1, faction != null ? faction : "");
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("updateFaction: " + e.getMessage()); }
    }

    /** Met à jour le killstreak (snapshot depuis KillstreakManager). */
    public void setStreak(UUID uuid, int streak) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET streak = ? WHERE uuid = ?")) {
            ps.setInt(1, streak);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("setStreak: " + e.getMessage()); }
    }

    /** Met à jour la prime active (snapshot depuis BountyManager). */
    public void setBounty(UUID uuid, long bounty) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_profiles SET bounty = ? WHERE uuid = ?")) {
            ps.setLong(1, bounty);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log("setBounty: " + e.getMessage()); }
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    /**
     * Retourne le profil complet d'un joueur (toutes les colonnes).
     * Retourne {@code null} si le joueur n'est pas dans la base.
     */
    public PlayerProfile getProfile(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT name, kills, deaths, playtime_s, balance, rank_label, faction, streak, bounty " +
                "FROM player_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerProfile(
                        uuid,
                        rs.getString("name"),
                        rs.getInt("kills"),
                        rs.getInt("deaths"),
                        rs.getLong("playtime_s"),
                        rs.getLong("balance"),
                        rs.getString("rank_label"),
                        rs.getString("faction"),
                        rs.getInt("streak"),
                        rs.getLong("bounty")
                    );
                }
            }
        } catch (SQLException e) { log("getProfile: " + e.getMessage()); }
        return null;
    }

    /**
     * Compatibilité avec l'ancien KsDatabase — retourne les stats KS uniquement.
     * Préférer {@link #getProfile(UUID)} pour le /profil.
     */
    public KsStats getStats(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM player_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new KsStats(
                        rs.getString("name"),
                        rs.getInt("kills"),
                        rs.getInt("deaths"),
                        rs.getLong("playtime_s")
                    );
                }
            }
        } catch (SQLException e) { log("getStats: " + e.getMessage()); }
        return null;
    }

    /** Top N joueurs par kills décroissants. */
    public List<KsStats> getTopKs(int limit) {
        List<KsStats> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT name, kills, deaths, playtime_s FROM player_profiles ORDER BY kills DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new KsStats(rs.getString("name"), rs.getInt("kills"),
                            rs.getInt("deaths"), rs.getLong("playtime_s")));
            }
        } catch (SQLException e) { log("getTopKs: " + e.getMessage()); }
        return list;
    }

    /** Rang du joueur dans le classement kills (1 = meilleur). Retourne -1 si absent. */
    public int getRank(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM player_profiles WHERE kills > " +
                "(SELECT COALESCE((SELECT kills FROM player_profiles WHERE uuid = ?), -1))")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) + 1;
            }
        } catch (SQLException e) { log("getRank: " + e.getMessage()); }
        return -1;
    }

    // ── Helpers internes ─────────────────────────────────────────────────────

    private void exec(String sql, String param) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
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
