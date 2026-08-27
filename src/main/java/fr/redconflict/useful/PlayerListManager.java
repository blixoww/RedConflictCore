package fr.redconflict.useful;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.staff.StaffManager;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scoreboard.*;

public class PlayerListManager {

    private final RedConflictCore plugin;
    private final StaffManager mgr = StaffManager.get();

    public PlayerListManager(RedConflictCore plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { update(); }
        }, 20L, 40L);
    }

    private void update() {
        // Récupérer le provider Vault Chat une seule fois par tick
        Chat vaultChat = null;
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) vaultChat = rsp.getProvider();
        } catch (Exception ignored) {}

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(board);
            }
            setupTeams(board, viewer, vaultChat);
            sendTabHeader(viewer);
        }
    }

    /**
     * Crée une team unique par joueur pour afficher son préfixe LuckPerms.
     * Le nom de la team commence par un index de tri pour garder l'ordre :
     *   00_ = vanish, 10_ = staff, 20_ = joueur normal
     */
    private void setupTeams(Scoreboard board, Player viewer, Chat vaultChat) {
        boolean viewerIsStaff = mgr.isStaff(viewer);

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean isVanished  = mgr.isVanished(p.getUniqueId());
            boolean isStaffMode = mgr.isInStaffMode(p.getUniqueId());
            boolean isStaff     = mgr.isStaff(p);

            // Cacher les vanish/staffmode aux non-staff
            if ((isVanished || isStaffMode) && !viewerIsStaff) {
                removeFromTeams(board, p.getName());
                continue;
            }

            // Préfixe de tri (pour l'ordre dans le tab)
            String sortPrefix;
            if (isVanished || isStaffMode) sortPrefix = "00_";
            else if (isStaff)              sortPrefix = "10_";
            else                           sortPrefix = "20_";

            String lpPrefix = "";
            if (vaultChat != null) {
                try {
                    String raw = vaultChat.getPlayerPrefix(p);
                    if (raw != null) {
                        // translateAlternateColorCodes et non replace('&','§') :
                        // le remplacement aveugle transformait aussi les & du
                        // texte ordinaire (« Rock & Roll ») en code de couleur.
                        lpPrefix = ChatColor.translateAlternateColorCodes('&', raw);
                    }
                } catch (Exception ignored) {}
            }

            // Le nom de la team doit être unique par joueur et <= 16 chars.
            String teamName = teamName(sortPrefix, p.getName());

            lpPrefix = safePrefix(lpPrefix, p);

            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
            }
            team.setPrefix(lpPrefix);

            // S'assurer que le joueur est dans la bonne team
            Team current = board.getEntryTeam(p.getName());
            if (current == null || !current.getName().equals(teamName)) {
                removeFromTeams(board, p.getName());
                team.addEntry(p.getName());
            }
        }

        // Nettoyage des teams orphelines (joueurs déconnectés)
        for (Team t : board.getTeams()) {
            if (t.getEntries().isEmpty()) t.unregister();
        }
    }


    /** Limite d'un préfixe de team en 1.8. Dépasser lève une exception côté Bukkit. */
    private static final int PREFIX_LIMIT = 16;

    /** Préfixes trop longs déjà signalés : on n'inonde pas la console. */
    private final java.util.Set<String> warnedPrefixes = new java.util.HashSet<String>();

    /**
     * Ramène un préfixe sous la limite de 16 caractères SANS jamais couper un
     * code de couleur en deux.
     *
     * <p><b>Le bug que ça corrige.</b> Un {@code substring(0, 16)} nu sur
     * {@code §8[§7Joueur§8] §f} (17 caractères) rendait
     * {@code §8[§7Joueur§8] §} — terminé par un {@code §} orphelin. Le client
     * lit alors la première lettre du pseudo comme le code qui manque : un
     * joueur dont le nom commence par « k » passait en {@code §k}, donc
     * illisible, un « c » en {@code §c}, donc rouge — et dans tous les cas la
     * lettre était mangée.
     *
     * <p>On coupe donc avant le {@code §} orphelin. Le préfixe perd sa dernière
     * couleur au lieu de dévorer le pseudo, et l'administrateur est prévenu une
     * fois en console : la vraie correction est de raccourcir le préfixe.
     */
    private String safePrefix(String prefix, Player owner) {
        if (prefix.length() <= PREFIX_LIMIT) {
            return prefix;
        }
        String cut = prefix.substring(0, PREFIX_LIMIT);
        if (cut.charAt(cut.length() - 1) == ChatColor.COLOR_CHAR) {
            cut = cut.substring(0, cut.length() - 1);
        }
        if (warnedPrefixes.add(prefix)) {
            plugin.getLogger().warning("[Tab] Préfixe de grade trop long pour "
                    + owner.getName() + " : « " + prefix.replace(ChatColor.COLOR_CHAR, '&') + " » fait "
                    + prefix.length() + " caractères, la limite 1.8 est " + PREFIX_LIMIT + ".");
            plugin.getLogger().warning("[Tab] Il est tronqué, donc le pseudo perd sa couleur. "
                    + "Raccourcis-le dans LuckPerms (retirer l'espace avant le dernier code suffit souvent).");
        }
        return cut;
    }

    /**
     * Nom de team unique et court.
     *
     * <p>Tronquer {@code sortPrefix + pseudo} à 16 caractères faisait collisionner
     * deux joueurs dont les noms partagent leurs 13 premières lettres : ils
     * atterrissaient dans la même team, donc avec le même préfixe. On remplace la
     * fin par une empreinte du nom complet quand il faut couper.
     */
    private static String teamName(String sortPrefix, String playerName) {
        String full = sortPrefix + playerName;
        if (full.length() <= 16) {
            return full;
        }
        String hash = Integer.toHexString(playerName.hashCode());
        int keep = 16 - sortPrefix.length() - hash.length();
        if (keep < 0) {
            keep = 0;
        }
        return sortPrefix + playerName.substring(0, Math.min(keep, playerName.length())) + hash;
    }
    private void sendTabHeader(Player p) {
        try {
            int online = countVisible(p);
            int max    = Bukkit.getMaxPlayers();
            String header = "\n\u00a7c\u00a7lRedConflict\n\u00a77Joueurs \u00a78: \u00a7f" + online + " \u00a78/ \u00a77" + max + "\n";

            // Monnaie
            Economy eco = VaultEconomy.get();
            String balanceStr = eco != null ? String.valueOf((long) eco.getBalance(p)) : "?";

            // Stats KS (kills, deaths, killstreak)
            int kills = 0;
            int deaths = 0;
            String ratio = "0.00";
            PlayerDatabase playerDatabase = plugin.getPlayerDatabase();
            if (playerDatabase != null) {
                PlayerDatabase.KsStats stats = playerDatabase.getStats(p.getUniqueId());
                if (stats != null) {
                    kills = stats.kills;
                    deaths = stats.deaths;
                    ratio = stats.ratio();
                }
            }

            // PB
            fr.redconflict.pb.PBManager pbMgr = plugin.getPBManager();
            String pbStr = pbMgr != null ? String.valueOf(pbMgr.get(p)) : "?";

            String footer = "\n"
                    + " \u00a78Monnaie \u00a78: \u00a77" + balanceStr + " \u00a78$\n"
                    + " \u00a78PB \u00a78: \u00a7e" + pbStr + " \u00a78PB\n"
                    + " \u00a78Kills \u00a78: \u00a7a" + kills
                    + " \u00a78| \u00a78Deaths \u00a78: \u00a7c" + deaths + "\n"
                    + " \u00a78Ratio K/D \u00a78: \u00a7e" + ratio + "\n";

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


    private void removeFromTeams(Scoreboard board, String entry) {
        for (Team t : board.getTeams()) { if (t.hasEntry(entry)) t.removeEntry(entry); }
    }

    private void sendPacket(Player player, Object packet, String ver) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object conn   = handle.getClass().getField("playerConnection").get(handle);
        conn.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server." + ver + ".Packet")).invoke(conn, packet);
    }
}




