package fr.originsfight.essentials.repository.h2;

import fr.originsfight.db.Database;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.HomeRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Homes en base H2, scopés par server-id (dialecte : MERGE ... KEY pour l'upsert).
 */
public class H2HomeRepository extends AbstractH2Repository implements HomeRepository {

    private final String serverId;

    public H2HomeRepository(Database db, String serverId, Logger logger) {
        super(db, logger);
        this.serverId = serverId;
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_homes ("
                + "uuid VARCHAR(36) NOT NULL, "
                + "server_id VARCHAR(32) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, "
                + locationColumns() + ", "
                + "PRIMARY KEY (uuid, server_id, name))");
    }

    @Override
    public Map<String, StoredLocation> findAll(UUID player) {
        Map<String, StoredLocation> homes = new LinkedHashMap<>();
        String sql = "SELECT name, world, x, y, z, yaw, pitch FROM ess_homes "
                + "WHERE uuid = ? AND server_id = ? ORDER BY name";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    homes.put(rs.getString("name"), readLocation(rs));
                }
            }
        } catch (SQLException e) {
            logError("lecture des homes", e);
        }
        return homes;
    }

    @Override
    public StoredLocation find(UUID player, String name) {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM ess_homes "
                + "WHERE uuid = ? AND server_id = ? AND name = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readLocation(rs);
            }
        } catch (SQLException e) {
            logError("lecture d'un home", e);
        }
        return null;
    }

    @Override
    public boolean exists(UUID player, String name) {
        return find(player, name) != null;
    }

    @Override
    public int count(UUID player) {
        String sql = "SELECT COUNT(*) FROM ess_homes WHERE uuid = ? AND server_id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logError("comptage des homes", e);
        }
        return 0;
    }

    @Override
    public void save(UUID player, String name, StoredLocation location) {
        String sql = "MERGE INTO ess_homes (uuid, server_id, name, world, x, y, z, yaw, pitch) "
                + "KEY (uuid, server_id, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            ps.setString(3, name);
            bindLocation(ps, 4, location);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("sauvegarde d'un home", e);
        }
    }

    @Override
    public boolean delete(UUID player, String name) {
        String sql = "DELETE FROM ess_homes WHERE uuid = ? AND server_id = ? AND name = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            ps.setString(3, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError("suppression d'un home", e);
            return false;
        }
    }
}
