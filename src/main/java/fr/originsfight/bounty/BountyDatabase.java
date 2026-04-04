package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base de données SQLite pour le système de primes (bounties).
 *
 * Tables :
 *   bounties       – primes actives
 *   bounty_refunds – remboursements en attente (joueurs hors-ligne au moment de l'expiration)
 */
public class BountyDatabase {

    private Connection connection;
    private final File dbFile;

    public BountyDatabase(OriginsFightCore plugin) {
        dbFile = new File(plugin.getDataFolder(), "bounties.db");
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    public boolean init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            return true;
        } catch (Exception e) {
            Bukkit.getLogger().severe("[Bounty] Erreur SQLite : " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) {}
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed())
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        return connection;
    }

    private void createTables() throws SQLException {
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS bounties (" +
            "  setter_uuid  TEXT NOT NULL," +
            "  setter_name  TEXT NOT NULL," +
            "  target_uuid  TEXT NOT NULL PRIMARY KEY," +
            "  target_name  TEXT NOT NULL," +
            "  amount       INTEGER NOT NULL," +
            "  created_at   INTEGER NOT NULL" +
            ")"
        );
        conn().createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS bounty_refunds (" +
            "  uuid   TEXT NOT NULL PRIMARY KEY," +
            "  amount INTEGER NOT NULL" +
            ")"
        );
    }

    // ── Primes actives ────────────────────────────────────────────────────────

    public void insertBounty(BountyInfo info) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT OR REPLACE INTO bounties (setter_uuid,setter_name,target_uuid,target_name,amount,created_at) VALUES (?,?,?,?,?,?)");
            ps.setString(1, info.getSetter().toString());
            ps.setString(2, info.getSetterName());
            ps.setString(3, info.getTarget().toString());
            ps.setString(4, info.getTargetName());
            ps.setLong(5, info.getAmount());
            ps.setLong(6, info.getTimestamp());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("insertBounty: " + e.getMessage()); }
    }

    public void deleteBounty(UUID targetUuid) {
        try {
            PreparedStatement ps = conn().prepareStatement("DELETE FROM bounties WHERE target_uuid = ?");
            ps.setString(1, targetUuid.toString());
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("deleteBounty: " + e.getMessage()); }
    }

    public List<BountyInfo> loadAllBounties() {
        List<BountyInfo> list = new ArrayList<>();
        try {
            ResultSet rs = conn().createStatement().executeQuery("SELECT * FROM bounties");
            while (rs.next()) {
                list.add(new BountyInfo(
                    UUID.fromString(rs.getString("setter_uuid")),
                    rs.getString("setter_name"),
                    UUID.fromString(rs.getString("target_uuid")),
                    rs.getString("target_name"),
                    rs.getLong("amount"),
                    rs.getLong("created_at")
                ));
            }
            rs.close();
        } catch (SQLException e) { log("loadAllBounties: " + e.getMessage()); }
        return list;
    }

    // ── Remboursements différés ───────────────────────────────────────────────

    /** Ajoute (ou cumule) un remboursement en attente pour un joueur hors-ligne. */
    public void addRefund(UUID uuid, long amount) {
        try {
            PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO bounty_refunds (uuid,amount) VALUES (?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount");
            ps.setString(1, uuid.toString());
            ps.setLong(2, amount);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { log("addRefund: " + e.getMessage()); }
    }

    /** Récupère et supprime le remboursement en attente pour un joueur (0 si aucun). */
    public long popRefund(UUID uuid) {
        long amount = 0;
        try {
            PreparedStatement sel = conn().prepareStatement(
                "SELECT amount FROM bounty_refunds WHERE uuid = ?");
            sel.setString(1, uuid.toString());
            ResultSet rs = sel.executeQuery();
            if (rs.next()) amount = rs.getLong("amount");
            rs.close(); sel.close();
            if (amount > 0) {
                PreparedStatement del = conn().prepareStatement(
                    "DELETE FROM bounty_refunds WHERE uuid = ?");
                del.setString(1, uuid.toString());
                del.executeUpdate(); del.close();
            }
        } catch (SQLException e) { log("popRefund: " + e.getMessage()); }
        return amount;
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static void log(String msg) {
        Bukkit.getLogger().warning("[Bounty-DB] " + msg);
    }
}

