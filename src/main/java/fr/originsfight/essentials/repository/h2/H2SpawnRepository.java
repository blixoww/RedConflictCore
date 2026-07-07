package fr.originsfight.essentials.repository.h2;

import fr.originsfight.db.Database;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.SpawnRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Spawn du serveur courant en base H2 (une ligne par server-id).
 */
public class H2SpawnRepository extends AbstractH2Repository implements SpawnRepository {

    private final String serverId;

    public H2SpawnRepository(Database db, String serverId, Logger logger) {
        super(db, logger);
        this.serverId = serverId;
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_spawns ("
                + "server_id VARCHAR(32) NOT NULL PRIMARY KEY, "
                + locationColumns() + ")");
    }

    @Override
    public StoredLocation find() {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM ess_spawns WHERE server_id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readLocation(rs);
            }
        } catch (SQLException e) {
            logError("lecture du spawn", e);
        }
        return null;
    }

    @Override
    public void save(StoredLocation location) {
        String sql = "MERGE INTO ess_spawns (server_id, world, x, y, z, yaw, pitch) "
                + "KEY (server_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            bindLocation(ps, 2, location);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("sauvegarde du spawn", e);
        }
    }
}
