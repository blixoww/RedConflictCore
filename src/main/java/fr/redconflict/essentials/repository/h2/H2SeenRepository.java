package fr.redconflict.essentials.repository.h2;

import fr.redconflict.db.Database;
import fr.redconflict.essentials.model.SeenRecord;
import fr.redconflict.essentials.repository.SeenRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Traces de connexion en base H2 (globales au cluster).
 */
public class H2SeenRepository extends AbstractH2Repository implements SeenRepository {

    public H2SeenRepository(Database db, Logger logger) {
        super(db, logger);
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_seen ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "name VARCHAR(32) NOT NULL, "
                + "first_join BIGINT NOT NULL, "
                + "last_join BIGINT NOT NULL, "
                + "last_quit BIGINT NOT NULL DEFAULT 0)");
    }

    @Override
    public SeenRecord find(UUID player) {
        String sql = "SELECT uuid, name, first_join, last_join, last_quit FROM ess_seen WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return read(rs);
            }
        } catch (SQLException e) {
            logError("lecture /seen", e);
        }
        return null;
    }

    @Override
    public SeenRecord findByName(String name) {
        // En cas de changement de pseudo, le plus récent l'emporte.
        String sql = "SELECT uuid, name, first_join, last_join, last_quit FROM ess_seen "
                + "WHERE LOWER(name) = LOWER(?) ORDER BY last_join DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return read(rs);
            }
        } catch (SQLException e) {
            logError("recherche /seen par nom", e);
        }
        return null;
    }

    @Override
    public void recordJoin(UUID player, String name, long timestamp) {
        String update = "UPDATE ess_seen SET name = ?, last_join = ? WHERE uuid = ?";
        String insert = "INSERT INTO ess_seen (uuid, name, first_join, last_join, last_quit) VALUES (?, ?, ?, ?, 0)";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, name);
                ps.setLong(2, timestamp);
                ps.setString(3, player.toString());
                if (ps.executeUpdate() > 0) return;
            }
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, player.toString());
                ps.setString(2, name);
                ps.setLong(3, timestamp);
                ps.setLong(4, timestamp);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logError("enregistrement connexion /seen", e);
        }
    }

    @Override
    public void recordQuit(UUID player, long timestamp) {
        String sql = "UPDATE ess_seen SET last_quit = ? WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, timestamp);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("enregistrement déconnexion /seen", e);
        }
    }

    private SeenRecord read(ResultSet rs) throws SQLException {
        return new SeenRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getLong("first_join"),
                rs.getLong("last_join"),
                rs.getLong("last_quit"));
    }
}
