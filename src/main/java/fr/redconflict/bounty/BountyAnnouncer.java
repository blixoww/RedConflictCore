package fr.redconflict.bounty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Annonces globales pour le système de killstreak / prime.
 */
public final class BountyAnnouncer {

    private static final String BAR = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    private BountyAnnouncer() {}

    public static void newBounty(String targetName, long amount, int killstreak) {
        String[] chat = {
            BAR,
            "  §4§l⚔ §c§lPRIME ACTIVE §4§l⚔",
            "  §8┃ §f§l" + targetName + " §7est en §c§lkillstreak de " + killstreak + " kills §7!",
            "  §8┃ §7Le serveur offre §f§l" + amount + "$ §7à celui qui l'éliminera.",
            "  §8┃ §e▶ §7Soyez le premier à l'abattre et récupérez la prime !",
            BAR
        };
        broadcast("§c§l⚔ PRIME ACTIVE ⚔", "§f" + targetName + " §7— killstreak §c§l" + killstreak, chat);
    }

    public static void escalatedBounty(String targetName, long oldAmount, long newAmount, int killstreak) {
        String[] chat = {
            BAR,
            "  §6§l⚠ §e§lPRIME AUGMENTÉE §6§l⚠",
            "  §8┃ §f§l" + targetName + " §7est toujours en vie ! §c§l" + killstreak + " kills §7!",
            "  §8┃ §7La prime est montée à §f§l" + newAmount + "$ §8(§7anciennement §f" + oldAmount + "$§8)§7 !",
            "  §8┃ §e▶ §7La récompense augmente — ne laissez pas passer ça !",
            BAR
        };
        broadcast("§6§l⚠ PRIME AUGMENTÉE ⚠", "§f" + targetName + " §8| §f§l" + newAmount + "$", chat);
    }

    public static void bountyClaimed(String killerName, String victimName, long amount, int killstreak) {
        String[] chat = {
            BAR,
            "  §2§l☠ §a§lPRIME RÉCLAMÉE §2§l☠",
            "  §8┃ §f§l" + killerName + " §7a mis fin au killstreak de §c§l" + victimName
                + " §8(§c" + killstreak + " kills§8)§7 !",
            "  §8┃ §7Il remporte la prime de §f§l" + amount + "$ §a!",
            BAR
        };
        broadcast("§a§l☠ PRIME RÉCLAMÉE ☠", "§f" + killerName + " §7a éliminé §c" + victimName, chat);
    }

    public static void factionBypassDetected(String killerName, String victimName) {
        String[] chat = {
            BAR,
            "  §c§l⚠ TENTATIVE DE CONTOURNEMENT DÉTECTÉE",
            "  §8┃ §f§l" + killerName + " §7a récemment quitté la faction de §c§l" + victimName + "§7.",
            "  §8┃ §c§lCe kill ne compte pas §7— suspicion de boost de prime.",
            "  §8┃ §7La prime reste active.",
            BAR
        };
        broadcast("§c§l⚠ CONTOURNEMENT DÉTECTÉ", "§fKill de §c" + victimName + " §7annulé", chat);
    }

    public static void killstreakMilestone(String playerName, int kills, long money, boolean hasItems) {
        StringBuilder reward = new StringBuilder();
        if (money > 0)  reward.append("§f§l").append(money).append("$");
        if (hasItems)   { if (reward.length() > 0) reward.append(" §8+ "); reward.append("§7items"); }
        if (reward.length() == 0) reward.append("§7—");

        String[] chat = {
            BAR,
            "  §e§l✦ §6§lKILLSTREAK ×" + kills + " §e§l✦",
            "  §8┃ §f§l" + playerName + " §7est en feu ! §e§l" + kills + " kills §7d'affilée !",
            "  §8┃ §7Récompense : " + reward,
            BAR
        };
        broadcast("§e§l✦ KILLSTREAK ×" + kills + " ✦", "§f" + playerName + " §7est en feu !", chat);
    }

    private static void broadcast(String title, String subtitle, String[] chat) {
        for (String line : chat) Bukkit.broadcastMessage(line);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle);
        }
    }
}
