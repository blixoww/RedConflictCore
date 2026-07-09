package fr.redconflict.essentials.repository.h2;

import fr.redconflict.db.Database;
import fr.redconflict.essentials.model.PlayerFlags;
import fr.redconflict.essentials.repository.PlayerStateRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * États joueur (god/fly) en base H2 (globaux au cluster).
 */
public class H2PlayerStateRepository extends AbstractH2Repository implements PlayerStateRepository {

    public H2PlayerStateRepository(Database db, Logger logger) {
        super(db, logger);
    }

    @Override
    public boolean init() {
        return createTable("CREATE TABLE IF NOT EXISTS ess_player_states ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "god BOOLEAN NOT NULL DEFAULT FALSE, "
                + "fly BOOLEAN NOT NULL DEFAULT FALSE)");
    }

    @Override
    public PlayerFlags find(UUID player) {
        String sql = "SELECT god, fly FROM ess_player_states WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new PlayerFlags(rs.getBoolean("god"), rs.getBoolean("fly"));
            }
        } catch (SQLException e) {
            logError("lecture des états joueur", e);
        }
        return PlayerFlags.NONE;
    }

    @Override
    public void saveGod(UUID player, boolean god) {
        saveFlag("god", player, god);
    }

    @Override
    public void saveFly(UUID player, boolean fly) {
        saveFlag("fly", player, fly);
    }

    /** Upsert d'une colonne booléenne ("god" ou "fly" — jamais de saisie utilisateur). */
    private void saveFlag(String column, UUID player, boolean value) {
        String update = "UPDATE ess_player_states SET " + column + " = ? WHERE uuid = ?";
        String insert = "INSERT INTO ess_player_states (uuid, " + column + ") VALUES (?, ?)";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setBoolean(1, value);
                ps.setString(2, player.toString());
                if (ps.executeUpdate() > 0) return;
            }
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, player.toString());
                ps.setBoolean(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logError("sauvegarde d'un état joueur", e);
        }
    }
}
