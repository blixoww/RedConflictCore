package fr.originsfight.useful;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.hdv.HdvManager;
import fr.originsfight.staff.StaffManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

public class PlayerListManager {

    private static final String TEAM_STAFF  = "00_staff";
    private static final String TEAM_NORMAL = "01_normal";
    private static final String TEAM_VANISH = "02_vanish";

    private final OriginsFightCore plugin;
    private final StaffManager mgr = StaffManager.get();

    public PlayerListManager(OriginsFightCore plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { update(); }
        }, 20L, 40L);
    }

    private void update() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(board);
            }
            Objective existing = board.getObjective(DisplaySlot.SIDEBAR);
            if (existing != null) existing.unregister();
            setupTeams(board, viewer);
            sendTabHeader(viewer);
        }
    }

    private void setupTeams(Scoreboard board, Player viewer) {
        boolean viewerIsStaff = mgr.isStaff(viewer);
        ensureTeam(board, TEAM_STAFF,  "\u00a7c");
        ensureTeam(board, TEAM_NORMAL, "\u00a7f");
        ensureTeam(board, TEAM_VANISH, "\u00a78");
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean isVanished  = mgr.isVanished(p.getUniqueId());
            boolean isStaffMode = mgr.isInStaffMode(p.getUniqueId());
            boolean isStaff     = mgr.isStaff(p);
            if ((isVanished || isStaffMode) && !viewerIsStaff) { removeFromTeams(board, p.getName()); continue; }
            String teamName;
            if (isVanished || isStaffMode) teamName = TEAM_VANISH;
            else if (isStaff)              teamName = TEAM_STAFF;
            else                           teamName = TEAM_NORMAL;
            Team current = board.getEntryTeam(p.getName());
            if (current == null || !current.getName().equals(teamName)) {
                removeFromTeams(board, p.getName());
                Team target = board.getTeam(teamName);
                if (target != null) target.addEntry(p.getName());
            }
        }
    }

    private void sendTabHeader(Player p) {
        try {
            int online = countVisible(p);
            int max    = Bukkit.getMaxPlayers();
            String header = "\n\u00a7c\u00a7lRedConflict\n\u00a77Joueurs \u00a78: \u00a7f" + online + " \u00a78/ \u00a77" + max + "\n";
            String footer =  "\n \u00a78Monnaies : \u00a77" + plugin.getEconomy().getBalance(p) + " \u00a78$";
            String ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> chatClass = Class.forName("net.minecraft.server." + ver + ".ChatComponentText");
            Class<?> pktClass  = Class.forName("net.minecraft.server." + ver + ".PacketPlayOutPlayerListHeaderFooter");
            Object pkt   = pktClass.newInstance();
            Object hChat = chatClass.getConstructor(String.class).newInstance(header);
            Object fChat = chatClass.getConstructor(String.class).newInstance(footer);
            java.lang.reflect.Field fa = pktClass.getDeclaredField("a");
            java.lang.reflect.Field fb = pktClass.getDeclaredField("b");
            fa.setAccessible(true); fb.setAccessible(true);
            fa.set(pkt, hChat); fb.set(pkt, fChat);
            sendPacket(p, pkt, ver);
        } catch (Exception ignored) {}
    }

    private int countVisible(Player viewer) {
        boolean isStaff = mgr.isStaff(viewer);
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isStaff && (mgr.isVanished(p.getUniqueId()) || mgr.isInStaffMode(p.getUniqueId()))) continue;
            count++;
        }
        return count;
    }

    private void ensureTeam(Scoreboard board, String name, String prefix) {
        Team team = board.getTeam(name);
        if (team == null) { team = board.registerNewTeam(name); team.setPrefix(prefix); }
    }

    private void removeFromTeams(Scoreboard board, String entry) {
        for (Team t : board.getTeams()) { if (t.hasEntry(entry)) t.removeEntry(entry); }
    }

    private void sendPacket(Player player, Object packet, String ver) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object conn   = handle.getClass().getField("playerConnection").get(handle);
        conn.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server." + ver + ".Packet")).invoke(conn, packet);
    }
}
