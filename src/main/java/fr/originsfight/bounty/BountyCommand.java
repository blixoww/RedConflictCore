package fr.originsfight.bounty;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Commande /prime (alias /bounty).
 *
 *   /prime              – primes actives
 *   /prime list         – idem
 *   /prime info [joueur]– killstreak & prime d'un joueur
 *   /prime top          – top killstreaks de la session
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private static final String BAR = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    private final BountyManager     bountyManager;
    private final KillstreakManager ksManager;

    public BountyCommand(BountyManager bm, KillstreakManager ksm) {
        this.bountyManager = bm;
        this.ksManager      = ksm;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;

        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        switch (sub) {
            case "list":  doList(player);       break;
            case "top":   doTop(player);        break;
            case "info":  doInfo(player, args); break;
            default:      sendHelp(player);     break;
        }
        return true;
    }

    // ── /prime list ───────────────────────────────────────────────────────────

    private void doList(Player player) {
        Map<UUID, BountyInfo> all = bountyManager.getActiveBounties();
        player.sendMessage(BAR);
        player.sendMessage("  §c§l⚔ §e§lPRIMES ACTIVES §c§l⚔  §8(" + all.size() + ")");
        if (all.isEmpty()) {
            player.sendMessage("  §8┃ §7Aucune prime active pour le moment.");
        } else {
            for (BountyInfo info : all.values()) {
                int ks = ksManager.getStreak(info.getTarget());
                player.sendMessage("  §8┃ §c§l" + info.getTargetName()
                    + " §8| §f§l" + info.getAmount() + "$ §8| §7killstreak : §e" + ks);
            }
        }
        player.sendMessage(BAR);
    }

    // ── /prime info [joueur] ──────────────────────────────────────────────────

    private void doInfo(Player player, String[] args) {
        UUID targetUuid;
        String targetName;
        if (args.length >= 2) {
            Player t = Bukkit.getPlayer(args[1]);
            if (t == null) { player.sendMessage(RC.PRE + "§cJoueur introuvable."); return; }
            targetUuid = t.getUniqueId(); targetName = t.getName();
        } else {
            targetUuid = player.getUniqueId(); targetName = player.getName();
        }

        int ks = ksManager.getStreak(targetUuid);
        BountyInfo info = bountyManager.getBounty(targetUuid);

        player.sendMessage(BAR);
        player.sendMessage("  §e§lInfos — §f" + targetName);
        player.sendMessage("  §8┃ §7Killstreak actuel : §e§l" + ks);
        if (info != null) {
            player.sendMessage("  §8┃ §7Prime active : §f§l" + info.getAmount() + "$");
            player.sendMessage("  §8┃ §7Killstreak à la création : §c" + info.getKillstreakAtCreation());
        } else {
            player.sendMessage("  §8┃ §7Aucune prime active sur ce joueur.");
        }
        List<Integer> thresholds = ksManager.getBountyThresholdKills();
        int next = nextThreshold(thresholds, ks);
        if (next > 0) {
            player.sendMessage("  §8┃ §7Prochain seuil : §c" + next + " kills §8(§7encore §c" + (next - ks) + "§8)");
        }
        player.sendMessage(BAR);
    }

    // ── /prime top ────────────────────────────────────────────────────────────

    private void doTop(Player player) {
        // Tri des killstreaks courants de la session (pas de persistance)
        Map<UUID, BountyInfo> bounties = bountyManager.getActiveBounties();

        // Récupérer tous les streaks de la session : joueurs avec prime + joueurs en streak sans prime
        // On affiche les joueurs qui ont une prime active pour simplifier
        player.sendMessage(BAR);
        player.sendMessage("  §e§l🏆 PRIMES ACTIVES — SESSION");
        if (bounties.isEmpty()) {
            player.sendMessage("  §8┃ §7Aucune prime active pour le moment.");
        } else {
            List<BountyInfo> sorted = bounties.values().stream()
                .sorted((a, b) -> Long.compare(b.getAmount(), a.getAmount()))
                .collect(Collectors.toList());
            int rank = 1;
            for (BountyInfo b : sorted) {
                int streak = ksManager.getStreak(b.getTarget());
                player.sendMessage("  §8┃ §7#" + rank + " §f" + b.getTargetName()
                    + " §8— §c" + b.getAmount() + "$ §8| §e" + streak + " kills");
                rank++;
            }
        }
        player.sendMessage(BAR);
    }

    // ── Aide ──────────────────────────────────────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage(BAR);
        player.sendMessage("  §e§l⚔ Commandes Primes & Killstreaks");
        player.sendMessage("  §8┃ §f/prime list             §8— §7Primes actives");
        player.sendMessage("  §8┃ §f/prime info [joueur]    §8— §7Killstreak & prime d'un joueur");
        player.sendMessage("  §8┃ §f/prime top              §8— §7Primes les plus élevées");
        player.sendMessage(BAR);
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String s : Arrays.asList("list", "info", "top")) {
                if (s.startsWith(prefix)) completions.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            String prefix = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) completions.add(p.getName());
            }
        }
        return completions;
    }

    private static int nextThreshold(List<Integer> thresholds, int current) {
        for (int t : thresholds) if (t > current) return t;
        return -1;
    }
}
