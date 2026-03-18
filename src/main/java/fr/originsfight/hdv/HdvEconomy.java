package fr.originsfight.hdv;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

public class HdvEconomy implements HdvManager.EconomyProvider {
    private static final Logger LOG = Logger.getLogger("HDV-Economy");

    private final Connection connection;

    public HdvEconomy(Connection connection) {
        this.connection = connection;
        createTable();
    }

    private void createTable() {
        try (Statement st = this.connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS hdv_economy (  uuid         TEXT PRIMARY KEY,  player_name  TEXT NOT NULL,  balance      INTEGER NOT NULL DEFAULT 0);");
        } catch (SQLException e) {
            LOG.severe("[HDV-Economy] Erreur crtable : " + e.getMessage());
        }
    }

    public long getBalance(Player player) {
        return getBalance(player.getUniqueId().toString());
    }

    public boolean withdraw(Player player, long amount) {
        if (amount <= 0L)
            return false;
        long current = getBalance(player);
        if (current < amount)
            return false;
        return setBalance(player.getUniqueId().toString(), player.getName(), current - amount);
    }

    public void deposit(Player player, long amount) {
        if (amount <= 0L)
            return;
        long current = getBalance(player);
        setBalance(player.getUniqueId().toString(), player.getName(), current + amount);
    }

    public long getBalance(String uuid) {
        try (PreparedStatement ps = this.connection.prepareStatement("SELECT balance FROM hdv_economy WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.warning("[HDV-Economy] getBalance error : " + e.getMessage());
        }
        return 0L;
    }

    public boolean setBalance(String uuid, String name, long amount) {
        if (amount < 0L)
            amount = 0L;
        try {
            String sql = "INSERT INTO hdv_economy (uuid, player_name, balance) VALUES (?,?,?) ON CONFLICT(uuid) DO UPDATE SET balance=excluded.balance, player_name=excluded.player_name";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, amount);
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            LOG.warning("[HDV-Economy] setBalance error : " + e.getMessage());
            return false;
        }
    }

    public boolean addBalance(String uuid, String name, long amount) {
        long cur = getBalance(uuid);
        return setBalance(uuid, name, cur + amount);
    }

    public boolean removeBalance(String uuid, String name, long amount) {
        long cur = getBalance(uuid);
        if (cur < amount)
            return false;
        return setBalance(uuid, name, cur - amount);
    }
}
