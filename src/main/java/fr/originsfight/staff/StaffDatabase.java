package fr.originsfight.staff;

import fr.originsfight.OriginsFightCore;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base de données SQLite pour le système staff.
 * <p>
 * Tables :
 * sanctions  → warn / mute / ban / kick
 * player_ips → liaison UUID ↔ IP (anti-contournement de ban)
 */
public class StaffDatabase {

    private Connection connection;
    private final File dbFile;

    public StaffDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "staff.db");
    }

    // ── Connexion ─────────────────────────────────────────────────────────────

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            return true;
        } catch (Exception e) {
            OriginsFightCore.getInstance().getLogger().severe("[Staff] Erreur SQLite : " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
        }
    }

    private Connection getConn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        return connection;
    }

    // ── Création des tables ───────────────────────────────────────────────────

    private void createTables() throws SQLException {
        try (Statement st = getConn().createStatement()) {
            // Sanctions : warn, mute, ban, kick
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS sanctions (" +
                            "  id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  uuid        TEXT    NOT NULL," +
                            "  name        TEXT    NOT NULL," +
                            "  type        TEXT    NOT NULL," +   // WARN / MUTE / BAN / KICK
                            "  reason      TEXT    NOT NULL," +
                            "  staff       TEXT    NOT NULL," +   // nom du staff
                            "  issued_at   INTEGER NOT NULL," +   // timestamp ms
                            "  expires_at  INTEGER," +            // null = permanent
                            "  active      INTEGER NOT NULL DEFAULT 1" + // 0 = levé
                            ")"
            );

            // Liaison UUID ↔ IPs (plusieurs IP par UUID, plusieurs UUID par IP)
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_ips (" +
                            "  uuid TEXT NOT NULL," +
                            "  ip   TEXT NOT NULL," +
                            "  name TEXT NOT NULL," +
                            "  PRIMARY KEY (uuid, ip)" +
                            ")"
            );

            // TopLuck : statistiques de minage par joueur
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS topluck (" +
                            "  uuid   TEXT NOT NULL," +
                            "  name   TEXT NOT NULL," +
                            "  block  TEXT NOT NULL," +
                            "  count  INTEGER NOT NULL DEFAULT 0," +
                            "  PRIMARY KEY (uuid, block)" +
                            ")"
            );
        }
    }

    // ── Sanctions ─────────────────────────────────────────────────────────────

    /**
     * Insère une sanction et retourne son ID.
     */
    public int addSanction(String uuid, String name, SanctionType type, String reason,
                           String staff, long expiresAt) {
        String sql = "INSERT INTO sanctions (uuid, name, type, reason, staff, issued_at, expires_at, active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, type.name());
            ps.setString(4, reason);
            ps.setString(5, staff);
            ps.setLong(6, System.currentTimeMillis());
            if (expiresAt <= 0) ps.setNull(7, Types.INTEGER);
            else ps.setLong(7, expiresAt);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            log("addSanction: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Lève une sanction active d'un type donné pour un UUID.
     */
    public boolean liftSanction(String uuid, SanctionType type) {
        String sql = "UPDATE sanctions SET active = 0 WHERE uuid = ? AND type = ? AND active = 1";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, type.name());
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            log("liftSanction: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remet à zéro TOUTES les sanctions actives d'un joueur (warn, mute, ban). Retourne le nombre de sanctions levées.
     */
    public int resetAllSanctions(String uuid) {
        String sql = "UPDATE sanctions SET active = 0 WHERE uuid = ? AND active = 1";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log("resetAllSanctions: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Retourne la sanction active d'un type pour un UUID, ou null.
     */
    public Sanction getActiveSanction(String uuid, SanctionType type) {
        String sql = "SELECT * FROM sanctions WHERE uuid = ? AND type = ? AND active = 1 " +
                "ORDER BY issued_at DESC LIMIT 1";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, type.name());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return fromResultSet(rs);
        } catch (SQLException e) {
            log("getActiveSanction: " + e.getMessage());
        }
        return null;
    }

    /**
     * Historique complet des sanctions d'un joueur (toutes actives ou non).
     */
    public List<Sanction> getHistory(String uuid) {
        List<Sanction> list = new ArrayList<>();
        String sql = "SELECT * FROM sanctions WHERE uuid = ? ORDER BY issued_at DESC";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(fromResultSet(rs));
        } catch (SQLException e) {
            log("getHistory: " + e.getMessage());
        }
        return list;
    }

    /**
     * Nettoie les sanctions expirées (les désactive).
     */
    public void cleanExpired() {
        String sql = "UPDATE sanctions SET active = 0 WHERE expires_at IS NOT NULL " +
                "AND expires_at < ? AND active = 1";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log("cleanExpired: " + e.getMessage());
        }
    }

    private Sanction fromResultSet(ResultSet rs) throws SQLException {
        long exp = rs.getLong("expires_at");
        return new Sanction(
                rs.getInt("id"),
                rs.getString("uuid"),
                rs.getString("name"),
                SanctionType.valueOf(rs.getString("type")),
                rs.getString("reason"),
                rs.getString("staff"),
                rs.getLong("issued_at"),
                rs.wasNull() ? -1 : exp,
                rs.getInt("active") == 1
        );
    }

    // ── IPs ───────────────────────────────────────────────────────────────────

    public void saveIp(String uuid, String name, String ip) {
        String sql = "INSERT OR REPLACE INTO player_ips (uuid, ip, name) VALUES (?, ?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, ip);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("saveIp: " + e.getMessage());
        }
    }

    /**
     * Retourne tous les UUIDs connus pour cette IP.
     */
    public List<String> getUuidsByIp(String ip) {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT uuid FROM player_ips WHERE ip = ?")) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getString("uuid"));
        } catch (SQLException e) {
            log("getUuidsByIp: " + e.getMessage());
        }
        return list;
    }

    // ── TopLuck ───────────────────────────────────────────────────────────────

    public void incrementBlock(String uuid, String name, String block) {
        try {
            PreparedStatement ins = getConn().prepareStatement(
                    "INSERT OR IGNORE INTO topluck (uuid, name, block, count) VALUES (?, ?, ?, 0)");
            ins.setString(1, uuid);
            ins.setString(2, name);
            ins.setString(3, block);
            ins.executeUpdate();
            ins.close();

            PreparedStatement upd = getConn().prepareStatement(
                    "UPDATE topluck SET count = count + 1, name = ? WHERE uuid = ? AND block = ?");
            upd.setString(1, name);
            upd.setString(2, uuid);
            upd.setString(3, block);
            upd.executeUpdate();
            upd.close();
        } catch (SQLException e) {
            log("incrementBlock: " + e.getMessage());
        }
    }

    /**
     * Retourne les données complètes de minage pour le TopLuck en UNE SEULE requête.
     * Chaque entrée contient : uuid, name, emerald, ruby, cobalt, total_modded, total_all.
     * Triées par total_modded décroissant.
     */
    public List<LuckData> getAllLuckData() {
        List<LuckData> list = new ArrayList<>();
        // On récupère : les 3 minerais moddés, leur total, et la stone cassée
        // Le ratio modded/stone est l'indicateur clé d'xray :
        //   ratio élevé (ex: 1 ruby pour 5 stone) = très suspect
        //   ratio faible (ex: 3 ruby pour 200 stone) = normal
        String sql =
                "SELECT uuid, MAX(name) as name," +
                "  SUM(CASE WHEN block='EMERALD_ORE' THEN count ELSE 0 END) as emerald," +
                "  SUM(CASE WHEN block='RUBY_ORE'    THEN count ELSE 0 END) as ruby," +
                "  SUM(CASE WHEN block='COBALT_ORE'  THEN count ELSE 0 END) as cobalt," +
                "  SUM(CASE WHEN block IN ('EMERALD_ORE','RUBY_ORE','COBALT_ORE') THEN count ELSE 0 END) as modded," +
                "  SUM(CASE WHEN block IN ('STONE','COBBLESTONE') THEN count ELSE 0 END) as stone" +
                " FROM topluck" +
                " GROUP BY uuid" +
                " HAVING modded > 0" +
                " ORDER BY modded DESC";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new LuckData(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getLong("emerald"),
                        rs.getLong("ruby"),
                        rs.getLong("cobalt"),
                        rs.getLong("modded"),
                        rs.getLong("stone")
                ));
            }
        } catch (SQLException e) {
            log("getAllLuckData: " + e.getMessage());
        }
        return list;
    }

    /**
     * Top N joueurs pour un bloc donné. Si block = null → top global.
     */
    public List<TopLuckEntry> getTopLuck(String block, int limit) {
        List<TopLuckEntry> list = new ArrayList<>();
        String sql;
        try {
            if (block == null) {
                sql = "SELECT uuid, name, SUM(count) as total FROM topluck " +
                        "GROUP BY uuid ORDER BY total DESC LIMIT ?";
                try (PreparedStatement ps = getConn().prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next())
                        list.add(new TopLuckEntry(rs.getString("uuid"), rs.getString("name"),
                                "TOTAL", rs.getLong("total")));
                }
            } else {
                sql = "SELECT uuid, name, count FROM topluck WHERE block = ? " +
                        "ORDER BY count DESC LIMIT ?";
                try (PreparedStatement ps = getConn().prepareStatement(sql)) {
                    ps.setString(1, block.toUpperCase());
                    ps.setInt(2, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next())
                        list.add(new TopLuckEntry(rs.getString("uuid"), rs.getString("name"),
                                block.toUpperCase(), rs.getLong("count")));
                }
            }
        } catch (SQLException e) {
            log("getTopLuck: " + e.getMessage());
        }
        return list;
    }

    public long getPlayerBlockCount(String uuid, String block) {
        String sql = "SELECT count FROM topluck WHERE uuid = ? AND block = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, block.toUpperCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("count");
        } catch (SQLException e) {
            log("getPlayerBlockCount: " + e.getMessage());
        }
        return 0;
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private void log(String msg) {
        OriginsFightCore.getInstance().getLogger().warning("[StaffDB] " + msg);
    }

    // ── Records internes ──────────────────────────────────────────────────────

    public enum SanctionType {WARN, MUTE, BAN, KICK}

    public static class Sanction {
        public final int id;
        public final String uuid, name, reason, staff;
        public final SanctionType type;
        public final long issuedAt, expiresAt; // -1 = permanent
        public final boolean active;

        public Sanction(int id, String uuid, String name, SanctionType type,
                        String reason, String staff, long issuedAt, long expiresAt, boolean active) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.type = type;
            this.reason = reason;
            this.staff = staff;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.active = active;
        }

        public boolean isPermanent() {
            return expiresAt <= 0;
        }

        public boolean isExpired() {
            return !isPermanent() && System.currentTimeMillis() > expiresAt;
        }
    }

    public static class TopLuckEntry {
        public final String uuid, name, block;
        public final long count;

        public TopLuckEntry(String uuid, String name, String block, long count) {
            this.uuid = uuid;
            this.name = name;
            this.block = block;
            this.count = count;
        }
    }

    public static class LuckData {
        public final String uuid, name;
        public final long emerald, ruby, cobalt, modded, stone;

        public LuckData(String uuid, String name, long emerald, long ruby, long cobalt, long modded, long stone) {
            this.uuid    = uuid;
            this.name    = name;
            this.emerald = emerald;
            this.ruby    = ruby;
            this.cobalt  = cobalt;
            this.modded  = modded;
            this.stone   = stone;
        }

        /**
         * Ratio moddés / stone. Indicateur clé d'xray.
         * Si stone == 0 : pas de référence, on retourne -1.
         */
        public double ratio() {
            if (stone <= 0) return -1;
            return (double) modded / stone;
        }

        /**
         * Retourne true si le ratio est suspect.
         * Seuil : 1 minerai moddé pour moins de 30 stone = xray probable.
         * Exemple : 1/30 = 0.033. Au-delà de 1/30 (soit ratio > 0.033) = suspect.
         */
        public boolean isSuspect(int minModded) {
            return modded >= minModded && ratio() > (1.0 / 30.0);
        }
    }
}

