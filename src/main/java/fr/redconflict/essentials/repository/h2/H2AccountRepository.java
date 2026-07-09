package fr.redconflict.essentials.repository.h2;

import fr.redconflict.db.Database;
import fr.redconflict.essentials.repository.AccountRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Comptes économie en base H2 (globaux au cluster : les soldes sont partagés
 * entre le Faction et le Minage via la base centrale).
 */
public class H2AccountRepository extends AbstractH2Repository implements AccountRepository {

    public H2AccountRepository(Database db, Logger logger) {
        super(db, logger);
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_accounts ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "name VARCHAR(32) NOT NULL, "
                + "balance DOUBLE PRECISION NOT NULL DEFAULT 0, "
                + "updated BIGINT NOT NULL DEFAULT 0)");
    }

    @Override
    public boolean exists(UUID player) {
        return findBalance(player) != null;
    }

    @Override
    public Double findBalance(UUID player) {
        String sql = "SELECT balance FROM ess_accounts WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            logError("lecture d'un solde", e);
        }
        return null;
    }

    @Override
    public UUID findUuidByName(String name) {
        String sql = "SELECT uuid FROM ess_accounts WHERE LOWER(name) = LOWER(?) ORDER BY updated DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
        } catch (SQLException e) {
            logError("résolution d'un compte par nom", e);
        }
        return null;
    }

    @Override
    public String findName(UUID player) {
        String sql = "SELECT name FROM ess_accounts WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logError("lecture du nom d'un compte", e);
        }
        return null;
    }

    @Override
    public void save(UUID player, String name, double balance) {
        String sql = "MERGE INTO ess_accounts (uuid, name, balance, updated) "
                + "KEY (uuid) VALUES (?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, name != null ? name : "");
            ps.setDouble(3, balance);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logError("sauvegarde d'un compte", e);
        }
    }
}
