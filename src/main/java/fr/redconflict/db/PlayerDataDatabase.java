package fr.redconflict.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stockage H2 (pool partagé) de l'état joueur transférable entre serveurs :
 * inventaire complet (contenu + armure + slot tenu), enderchest, XP, vie, faim et effets de potion.
 *
 * Table {@code player_data} — une ligne par joueur :
 *   uuid         VARCHAR PK
 *   inv_main     BYTEA    — 36 slots (contenu principal + hotbar), encodé via {@link ItemArrayCodec}
 *   inv_armor    BYTEA    — 4 slots d'armure
 *   held_slot    INT      — index de la hotbar tenue (0-8)
 *   ender        BYTEA    — 27 slots d'enderchest
 *   exp_level    INT      — niveau d'XP
 *   exp_progress REAL     — progression vers le niveau suivant (0.0–1.0)
 *   health       DOUBLE   — points de vie
 *   food         INT      — niveau de faim (0-20)
 *   saturation   REAL     — saturation
 *   effects      BYTEA    — effets de potion actifs, encodés via {@link PotionEffectCodec}
 *   updated_at   BIGINT   — timestamp ms de la dernière sauvegarde
 */
public class PlayerDataDatabase {

    private static final Logger LOG = Logger.getLogger("PlayerDataSync");

    private final Database db;

    public PlayerDataDatabase(Database db) {
        this.db = db;
    }

    public boolean init() {
        try (Connection c = db.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_data (" +
                "  uuid         VARCHAR(36) PRIMARY KEY," +
                "  inv_main     BYTEA," +
                "  inv_armor    BYTEA," +
                "  held_slot    INT    NOT NULL DEFAULT 0," +
                "  ender        BYTEA," +
                "  exp_level    INT    NOT NULL DEFAULT 0," +
                "  exp_progress REAL   NOT NULL DEFAULT 0," +
                "  health       DOUBLE NOT NULL DEFAULT 20," +
                "  food         INT    NOT NULL DEFAULT 20," +
                "  saturation   REAL   NOT NULL DEFAULT 5," +
                "  effects      BYTEA," +
                "  updated_at   BIGINT NOT NULL DEFAULT 0" +
                ")");
            // Migration des bases créées par un lot antérieur (inventaire seul) : ajoute les
            // colonnes manquantes sans toucher aux données existantes. H2 supporte IF NOT EXISTS.
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS exp_level    INT    NOT NULL DEFAULT 0");
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS exp_progress REAL   NOT NULL DEFAULT 0");
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS health       DOUBLE NOT NULL DEFAULT 20");
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS food         INT    NOT NULL DEFAULT 20");
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS saturation   REAL   NOT NULL DEFAULT 5");
            st.executeUpdate("ALTER TABLE player_data ADD COLUMN IF NOT EXISTS effects      BYTEA");
            return true;
        } catch (SQLException e) {
            LOG.severe("[Sync] init: " + e.getMessage());
            return false;
        }
    }

    /** {@code true} si une ligne de données existe pour ce joueur. */
    public boolean has(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) { LOG.warning("[Sync] has: " + e.getMessage()); return false; }
    }

    /** Sauvegarde (upsert) l'état complet du joueur. */
    public void save(UUID uuid, PlayerData d) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "MERGE INTO player_data (uuid, inv_main, inv_armor, held_slot, ender, " +
                "exp_level, exp_progress, health, food, saturation, effects, updated_at) " +
                "KEY(uuid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, d.invMain);
            ps.setBytes(3, d.invArmor);
            ps.setInt(4, d.heldSlot);
            ps.setBytes(5, d.ender);
            ps.setInt(6, d.expLevel);
            ps.setFloat(7, d.expProgress);
            ps.setDouble(8, d.health);
            ps.setInt(9, d.food);
            ps.setFloat(10, d.saturation);
            ps.setBytes(11, d.effects);
            ps.setLong(12, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Sync] save(" + uuid + "): " + e.getMessage()); }
    }

    /** Charge l'état du joueur, ou {@code null} si absent. */
    public PlayerData load(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT inv_main, inv_armor, held_slot, ender, exp_level, exp_progress, " +
                "health, food, saturation, effects FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerData d = new PlayerData();
                    d.invMain     = rs.getBytes("inv_main");
                    d.invArmor    = rs.getBytes("inv_armor");
                    d.heldSlot    = rs.getInt("held_slot");
                    d.ender       = rs.getBytes("ender");
                    d.expLevel    = rs.getInt("exp_level");
                    d.expProgress = rs.getFloat("exp_progress");
                    d.health      = rs.getDouble("health");
                    d.food        = rs.getInt("food");
                    d.saturation  = rs.getFloat("saturation");
                    d.effects     = rs.getBytes("effects");
                    return d;
                }
            }
        } catch (SQLException e) { LOG.severe("[Sync] load(" + uuid + "): " + e.getMessage()); }
        return null;
    }

    /** DTO brut de l'état joueur transférable. */
    public static class PlayerData {
        public byte[] invMain;
        public byte[] invArmor;
        public int    heldSlot;
        public byte[] ender;
        public int    expLevel;
        public float  expProgress;
        public double health      = 20.0D;
        public int    food        = 20;
        public float  saturation  = 5.0F;
        public byte[] effects;
    }
}
