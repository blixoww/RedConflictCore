package fr.originsfight.essentials.repository.h2;

import fr.originsfight.db.Database;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.BackRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Positions /back en base H2, scopées par server-id.
 */
public class H2BackRepository extends AbstractH2Repository implements BackRepository {

    private final String serverId;

    public H2BackRepository(Database db, String serverId, Logger logger) {
        super(db, logger);
        this.serverId = serverId;
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_back ("
                + "uuid VARCHAR(36) NOT NULL, "
                + "server_id VARCHAR(32) NOT NULL, "
                + locationColumns() + ", "
                + "PRIMARY KEY (uuid, server_id))");
    }

    @Override
    public StoredLocation find(UUID player) {
        String sql = "SELECT world, x, y, z, yaw, pitch FROM ess_back WHERE uuid = ? AND server_id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readLocation(rs);
            }
        } catch (SQLException e) {
            logError("lecture position /back", e);
        }
        return null;
    }

    @Override
    public void save(UUID player, StoredLocation location) {
        String sql = "MERGE INTO ess_back (uuid, server_id, world, x, y, z, yaw, pitch) "
                + "KEY (uuid, server_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, serverId);
            bindLocation(ps, 3, location);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("sauvegarde position /back", e);
        }
    }
}
