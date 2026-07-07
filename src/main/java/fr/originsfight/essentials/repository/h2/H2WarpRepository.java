package fr.originsfight.essentials.repository.h2;

import fr.originsfight.db.Database;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.WarpRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Warps en base H2, scopés par server-id.
 */
public class H2WarpRepository extends AbstractH2Repository implements WarpRepository {

    private final String serverId;

    public H2WarpRepository(Database db, String serverId, Logger logger) {
        super(db, logger);
        this.serverId = serverId;
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_warps ("
                + "server_id VARCHAR(32) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, "
                + locationColumns() + ", "
                + "PRIMARY KEY (server_id, name))");
    }

    @Override
    public StoredLocation find(String name) {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM ess_warps WHERE server_id = ? AND name = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readLocation(rs);
            }
        } catch (SQLException e) {
            logError("lecture d'un warp", e);
        }
        return null;
    }

    @Override
    public List<String> names() {
        List<String> names = new ArrayList<>();
        String sql = "SELECT name FROM ess_warps WHERE server_id = ? ORDER BY name";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            logError("liste des warps", e);
        }
        return names;
    }

    @Override
    public void save(String name, StoredLocation location) {
        String sql = "MERGE INTO ess_warps (server_id, name, world, x, y, z, yaw, pitch) "
                + "KEY (server_id, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, name);
            bindLocation(ps, 3, location);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("sauvegarde d'un warp", e);
        }
    }

    @Override
    public boolean delete(String name) {
        String sql = "DELETE FROM ess_warps WHERE server_id = ? AND name = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError("suppression d'un warp", e);
            return false;
        }
    }
}
