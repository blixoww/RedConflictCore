package fr.redconflict.essentials.repository.h2;

import fr.redconflict.db.Database;
import fr.redconflict.essentials.repository.IgnoreRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Paires "joueur → ignoré" en base H2 (globales au cluster).
 */
public class H2IgnoreRepository extends AbstractH2Repository implements IgnoreRepository {

    public H2IgnoreRepository(Database db, Logger logger) {
        super(db, logger);
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_ignores ("
                + "uuid VARCHAR(36) NOT NULL, "
                + "ignored VARCHAR(36) NOT NULL, "
                + "PRIMARY KEY (uuid, ignored))");
    }

    @Override
    public Set<UUID> findIgnored(UUID player) {
        Set<UUID> ignored = new HashSet<>();
        String sql = "SELECT ignored FROM ess_ignores WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ignored.add(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException e) {
            logError("lecture des ignorés", e);
        }
        return ignored;
    }

    @Override
    public void add(UUID player, UUID ignored) {
        String sql = "MERGE INTO ess_ignores (uuid, ignored) KEY (uuid, ignored) VALUES (?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, ignored.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("ajout d'un ignoré", e);
        }
    }

    @Override
    public void remove(UUID player, UUID ignored) {
        String sql = "DELETE FROM ess_ignores WHERE uuid = ? AND ignored = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, ignored.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("retrait d'un ignoré", e);
        }
    }
}
