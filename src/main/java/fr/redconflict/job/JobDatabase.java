package fr.redconflict.job;

import fr.redconflict.db.Database;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Accès H2 (pool partagé) pour les données de métiers.
 *
 * Table player_jobs :
 *   uuid           VARCHAR PK
 *   job            VARCHAR DEFAULT 'NONE'
 *   miner_level    INT     DEFAULT 0
 *   miner_xp       INT     DEFAULT 0
 *   farmer_level   INT     DEFAULT 0
 *   farmer_xp      INT     DEFAULT 0
 *   artisan_level  INT     DEFAULT 0
 *   artisan_xp     INT     DEFAULT 0
 */
public class JobDatabase {

    private static final Logger LOG = Logger.getLogger("Jobs");
    private final Database db;

    public JobDatabase(Database db) {
        this.db = db;
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    public boolean connect() {
        try (Connection c = db.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_jobs (" +
                "  uuid           VARCHAR(36) PRIMARY KEY," +
                "  job            VARCHAR(16) NOT NULL DEFAULT 'NONE'," +
                "  miner_level    INT NOT NULL DEFAULT 0," +
                "  miner_xp       INT NOT NULL DEFAULT 0," +
                "  farmer_level   INT NOT NULL DEFAULT 0," +
                "  farmer_xp      INT NOT NULL DEFAULT 0," +
                "  artisan_level  INT NOT NULL DEFAULT 0," +
                "  artisan_xp     INT NOT NULL DEFAULT 0" +
                ")"
            );
            // Snapshot figé des classements (recalculé toutes les 24 h / au démarrage / via commande admin).
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS job_top_snapshot (" +
                "  category  VARCHAR(16) NOT NULL," +
                "  rk        INT         NOT NULL," +  // 'rank' est réservé en H2 2.x
                "  uuid      VARCHAR(36)," +
                "  name      VARCHAR(48)," +
                "  job       VARCHAR(16)," +
                "  level     INT," +
                "  xp        INT," +
                "  PRIMARY KEY (category, rk)" +
                ")"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS job_top_meta (" +
                "  k VARCHAR(32) PRIMARY KEY," +
                "  v VARCHAR(64)" +
                ")"
            );
            return true;
        } catch (SQLException e) {
            LOG.severe("[Jobs] Erreur H2 : " + e.getMessage());
            return false;
        }
    }

    /** No-op : le pool est fermé centralement. */
    public void disconnect() { }

    // ── Ensure row ────────────────────────────────────────────────────────────

    public void ensurePlayer(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player_jobs (uuid) SELECT ? " +
                "WHERE NOT EXISTS (SELECT 1 FROM player_jobs WHERE uuid = ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { LOG.warning("[Jobs] ensurePlayer: " + e.getMessage()); }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    public JobData loadPlayer(UUID uuid) {
        ensurePlayer(uuid);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT miner_level,miner_xp,farmer_level,farmer_xp,artisan_level,artisan_xp " +
                "FROM player_jobs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                JobData d = new JobData();
                if (rs.next()) {
                    d.minerLevel    = rs.getInt("miner_level");
                    d.minerXp       = rs.getInt("miner_xp");
                    d.farmerLevel   = rs.getInt("farmer_level");
                    d.farmerXp      = rs.getInt("farmer_xp");
                    d.artisanLevel  = rs.getInt("artisan_level");
                    d.artisanXp     = rs.getInt("artisan_xp");
                }
                return d;
            }
        } catch (SQLException e) {
            LOG.warning("[Jobs] loadPlayer: " + e.getMessage());
            return new JobData();
        }
    }

    // ── Écriture ──────────────────────────────────────────────────────────────

    public void savePlayer(UUID uuid, JobData d) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO player_jobs " +
                "(uuid,miner_level,miner_xp,farmer_level,farmer_xp,artisan_level,artisan_xp) " +
                "KEY(uuid) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, d.minerLevel);
            ps.setInt(3, d.minerXp);
            ps.setInt(4, d.farmerLevel);
            ps.setInt(5, d.farmerXp);
            ps.setInt(6, d.artisanLevel);
            ps.setInt(7, d.artisanXp);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.warning("[Jobs] savePlayer: " + e.getMessage()); }
    }

    public void setJob(UUID uuid, JobType job) { /* conservé pour compatibilité ascendante */ }

    public void addXp(UUID uuid, JobType job, int xpGain, int newLevel, int newXp) {
        String levelCol = levelCol(job);
        String xpCol    = xpCol(job);
        if (levelCol == null) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE player_jobs SET " + levelCol + " = ?, " + xpCol + " = ? WHERE uuid = ?")) {
            ps.setInt(1, newLevel);
            ps.setInt(2, newXp);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { LOG.warning("[Jobs] addXp: " + e.getMessage()); }
    }

    // ── Classement ────────────────────────────────────────────────────────────

    public List<TopEntry> getTop(JobType job, int limit) {
        List<TopEntry> list = new ArrayList<>();
        String levelCol = job.isReal() ? levelCol(job) : null;
        String xpCol    = job.isReal() ? xpCol(job) : null;
        String sql;
        if (job.isReal()) {
            // Classement par métier spécifique : inclut les joueurs ayant de l'XP
            // même au niveau 0 (sinon le classement reste vide tant que personne n'a level up).
            sql = "SELECT uuid, job, " + levelCol + " AS lvl, " + xpCol + " AS xp " +
                  "FROM player_jobs WHERE " + levelCol + " > 0 OR " + xpCol + " > 0 " +
                  "ORDER BY lvl DESC, xp DESC LIMIT " + limit;
        } else {
            // Classement global: niveau le plus élevé parmi les 3 métiers (GREATEST = max multi-args H2)
            sql = "SELECT uuid, job, " +
                  "GREATEST(miner_level, farmer_level, artisan_level) AS lvl, " +
                  "CASE WHEN miner_level >= farmer_level AND miner_level >= artisan_level THEN miner_xp " +
                  "     WHEN farmer_level >= artisan_level THEN farmer_xp ELSE artisan_xp END AS xp " +
                  "FROM player_jobs WHERE " +
                  "(miner_level > 0 OR farmer_level > 0 OR artisan_level > 0 " +
                  " OR miner_xp > 0 OR farmer_xp > 0 OR artisan_xp > 0) " +
                  "ORDER BY lvl DESC, xp DESC LIMIT " + limit;
        }
        try (Connection c = db.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                TopEntry e = new TopEntry();
                e.uuid  = rs.getString("uuid");
                e.job   = job.isReal() ? job : JobType.fromString(rs.getString("job"));
                e.level = rs.getInt("lvl");
                e.xp    = rs.getInt("xp");
                list.add(e);
            }
        } catch (SQLException ex) { LOG.warning("[Jobs] getTop: " + ex.getMessage()); }
        return list;
    }

    // ── Snapshot figé des classements ──────────────────────────────────────────

    /**
     * Remplace le snapshot persistant par {@code categories} et enregistre l'horodatage.
     * Opération transactionnelle (purge + réinsertion).
     */
    public void saveSnapshot(Map<String, List<TopEntry>> categories, long timestamp) {
        try (Connection c = db.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM job_top_snapshot");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO job_top_snapshot (category,rk,uuid,name,job,level,xp) " +
                        "VALUES (?,?,?,?,?,?,?)")) {
                    for (Map.Entry<String, List<TopEntry>> cat : categories.entrySet()) {
                        int rank = 1;
                        for (TopEntry e : cat.getValue()) {
                            ps.setString(1, cat.getKey());
                            ps.setInt(2, rank++);
                            ps.setString(3, e.uuid);
                            ps.setString(4, e.name);
                            ps.setString(5, e.job.name());
                            ps.setInt(6, e.level);
                            ps.setInt(7, e.xp);
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO job_top_meta (k,v) KEY(k) VALUES ('last_update', ?)")) {
                    ps.setString(1, Long.toString(timestamp));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            LOG.warning("[Jobs] saveSnapshot: " + e.getMessage());
        }
    }

    /** Recharge le snapshot persistant en mémoire (catégorie → liste ordonnée par rang). */
    public Map<String, List<TopEntry>> loadSnapshot() {
        Map<String, List<TopEntry>> map = new HashMap<>();
        try (Connection c = db.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT category,uuid,name,job,level,xp FROM job_top_snapshot ORDER BY category, rk")) {
            while (rs.next()) {
                TopEntry e = new TopEntry();
                e.uuid  = rs.getString("uuid");
                e.name  = rs.getString("name");
                e.job   = JobType.fromString(rs.getString("job"));
                e.level = rs.getInt("level");
                e.xp    = rs.getInt("xp");
                map.computeIfAbsent(rs.getString("category"), k -> new ArrayList<>()).add(e);
            }
        } catch (SQLException ex) { LOG.warning("[Jobs] loadSnapshot: " + ex.getMessage()); }
        return map;
    }

    /** Horodatage (ms) du dernier recalcul du snapshot, 0 si jamais calculé. */
    public long loadSnapshotTimestamp() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT v FROM job_top_meta WHERE k = 'last_update'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                try { return Long.parseLong(rs.getString("v")); } catch (NumberFormatException ignored) {}
            }
        } catch (SQLException ex) { LOG.warning("[Jobs] loadSnapshotTimestamp: " + ex.getMessage()); }
        return 0L;
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
