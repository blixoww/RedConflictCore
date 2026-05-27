package fr.originsfight.job;

import fr.originsfight.OriginsFightCore;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Accès SQLite pour les données de métiers.
 *
 * Table player_jobs :
 *   uuid           TEXT PK
 *   job            TEXT  DEFAULT 'NONE'
 *   miner_level    INT   DEFAULT 0
 *   miner_xp       INT   DEFAULT 0
 *   farmer_level   INT   DEFAULT 0
 *   farmer_xp      INT   DEFAULT 0
 *   artisan_level  INT   DEFAULT 0
 *   artisan_xp     INT   DEFAULT 0
 */
public class JobDatabase {

    private static final Logger LOG = Logger.getLogger("Jobs");
    private Connection conn;
    private final File dbFile;

    public JobDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "jobs/jobs.db");
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    public boolean connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            dbFile.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            return true;
        } catch (Exception e) {
            LOG.severe("[Jobs] Erreur SQLite : " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        try { if (conn != null && !conn.isClosed()) conn.close(); } catch (SQLException ignored) {}
    }

    private Connection c() throws SQLException {
        if (conn == null || conn.isClosed())
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        return conn;
    }

    private void createTables() throws SQLException {
        c().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS player_jobs (" +
            "  uuid           TEXT    PRIMARY KEY," +
            "  job            TEXT    NOT NULL DEFAULT 'NONE'," +
            "  miner_level    INTEGER NOT NULL DEFAULT 0," +
            "  miner_xp       INTEGER NOT NULL DEFAULT 0," +
            "  farmer_level   INTEGER NOT NULL DEFAULT 0," +
            "  farmer_xp      INTEGER NOT NULL DEFAULT 0," +
            "  artisan_level  INTEGER NOT NULL DEFAULT 0," +
            "  artisan_xp     INTEGER NOT NULL DEFAULT 0" +
            ")"
        );
    }

    // ── Ensure row ────────────────────────────────────────────────────────────

    public void ensurePlayer(UUID uuid) {
        try {
            PreparedStatement ps = c().prepareStatement(
                "INSERT OR IGNORE INTO player_jobs (uuid) VALUES (?)");
            ps.setString(1, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { LOG.warning("[Jobs] ensurePlayer: " + e.getMessage()); }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    public JobData loadPlayer(UUID uuid) {
        ensurePlayer(uuid);
        try {
            PreparedStatement ps = c().prepareStatement(
                "SELECT miner_level,miner_xp,farmer_level,farmer_xp,artisan_level,artisan_xp " +
                "FROM player_jobs WHERE uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            JobData d = new JobData();
            if (rs.next()) {
                d.minerLevel    = rs.getInt("miner_level");
                d.minerXp       = rs.getInt("miner_xp");
                d.farmerLevel   = rs.getInt("farmer_level");
                d.farmerXp      = rs.getInt("farmer_xp");
                d.artisanLevel  = rs.getInt("artisan_level");
                d.artisanXp     = rs.getInt("artisan_xp");
            }
            rs.close(); ps.close();
            return d;
        } catch (SQLException e) {
            LOG.warning("[Jobs] loadPlayer: " + e.getMessage());
            return new JobData();
        }
    }

    // ── Écriture ──────────────────────────────────────────────────────────────

    public void savePlayer(UUID uuid, JobData d) {
        try {
            PreparedStatement ps = c().prepareStatement(
                "INSERT OR REPLACE INTO player_jobs " +
                "(uuid,miner_level,miner_xp,farmer_level,farmer_xp,artisan_level,artisan_xp) " +
                "VALUES (?,?,?,?,?,?,?)");
            ps.setString(1, uuid.toString());
            ps.setInt(2, d.minerLevel);
            ps.setInt(3, d.minerXp);
            ps.setInt(4, d.farmerLevel);
            ps.setInt(5, d.farmerXp);
            ps.setInt(6, d.artisanLevel);
            ps.setInt(7, d.artisanXp);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { LOG.warning("[Jobs] savePlayer: " + e.getMessage()); }
    }

    public void setJob(UUID uuid, JobType job) { /* conservé pour compatibilité ascendante */ }

    public void addXp(UUID uuid, JobType job, int xpGain, int newLevel, int newXp) {
        String levelCol = levelCol(job);
        String xpCol    = xpCol(job);
        if (levelCol == null) return;
        try {
            PreparedStatement ps = c().prepareStatement(
                "UPDATE player_jobs SET " + levelCol + " = ?, " + xpCol + " = ? WHERE uuid = ?");
            ps.setInt(1, newLevel);
            ps.setInt(2, newXp);
            ps.setString(3, uuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { LOG.warning("[Jobs] addXp: " + e.getMessage()); }
    }

    // ── Classement ────────────────────────────────────────────────────────────

    public List<TopEntry> getTop(JobType job, int limit) {
        List<TopEntry> list = new ArrayList<>();
        String levelCol = job.isReal() ? levelCol(job) : null;
        String xpCol    = job.isReal() ? xpCol(job) : null;
        try {
            String sql;
            if (job.isReal()) {
                // Classement par métier spécifique
                sql = "SELECT uuid, job, " + levelCol + " AS lvl, " + xpCol + " AS xp " +
                      "FROM player_jobs WHERE " + levelCol + " > 0 " +
                      "ORDER BY lvl DESC, xp DESC LIMIT " + limit;
            } else {
                // Classement global: niveau le plus élevé parmi les 3 métiers
                sql = "SELECT uuid, job, " +
                      "MAX(miner_level, farmer_level, artisan_level) AS lvl, " +
                      "CASE WHEN miner_level >= farmer_level AND miner_level >= artisan_level THEN miner_xp " +
                      "     WHEN farmer_level >= artisan_level THEN farmer_xp ELSE artisan_xp END AS xp " +
                      "FROM player_jobs WHERE " +
                      "(miner_level > 0 OR farmer_level > 0 OR artisan_level > 0) " +
                      "ORDER BY lvl DESC, xp DESC LIMIT " + limit;
            }
            ResultSet rs = c().createStatement().executeQuery(sql);
            while (rs.next()) {
                TopEntry e = new TopEntry();
                e.uuid  = rs.getString("uuid");
                e.job   = job.isReal() ? job : JobType.fromString(rs.getString("job"));
                e.level = rs.getInt("lvl");
                e.xp    = rs.getInt("xp");
                list.add(e);
            }
            rs.close();
        } catch (SQLException ex) { LOG.warning("[Jobs] getTop: " + ex.getMessage()); }
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String levelCol(JobType job) {
        switch (job) {
            case MINER:   return "miner_level";
            case FARMER:  return "farmer_level";
            case ARTISAN: return "artisan_level";
            default: return null;
        }
    }

    private String xpCol(JobType job) {
        switch (job) {
            case MINER:   return "miner_xp";
            case FARMER:  return "farmer_xp";
            case ARTISAN: return "artisan_xp";
            default: return null;
        }
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    public static class JobData {
        public int minerLevel       = 0;
        public int minerXp          = 0;
        public int farmerLevel      = 0;
        public int farmerXp         = 0;
        public int artisanLevel     = 0;
        public int artisanXp        = 0;

        public int getLevelFor(JobType job) {
            switch (job) {
                case MINER:   return minerLevel;
                case FARMER:  return farmerLevel;
                case ARTISAN: return artisanLevel;
                default: return 0;
            }
        }
        public int getXpFor(JobType job) {
            switch (job) {
                case MINER:   return minerXp;
                case FARMER:  return farmerXp;
                case ARTISAN: return artisanXp;
                default: return 0;
            }
        }
        public void setLevel(JobType job, int lvl) {
            switch (job) {
                case MINER:   minerLevel   = lvl; break;
                case FARMER:  farmerLevel  = lvl; break;
                case ARTISAN: artisanLevel = lvl; break;
                default: break;
            }
        }
        public void setXp(JobType job, int xp) {
            switch (job) {
                case MINER:   minerXp   = xp; break;
                case FARMER:  farmerXp  = xp; break;
                case ARTISAN: artisanXp = xp; break;
                default: break;
            }
        }
    }

    public static class TopEntry {
        public String  uuid;
        public String  name  = "?";
        public JobType job   = JobType.NONE;
        public int     level = 0;
        public int     xp    = 0;
    }
}



