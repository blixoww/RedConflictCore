package fr.redconflict.bounty;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.faction.FactionHook;
import fr.redconflict.friend.FriendManager;
import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.redfaction.entity.Relation;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /prime (alias /bounty) — primes manuelles et consultation des killstreaks.
 *
 * <p>Sous-commandes : {@code <joueur> <montant>} (poser), {@code cancel},
 * {@code list}, {@code info [joueur]}, {@code top}.
 *
 * <p>Restrictions : impossible de cibler un membre de sa faction, un allié,
 * une trêve ou un ami ; délai de 24 h entre deux primes sur la même cible.
 */
public class BountyCommand extends CoreCommand {

    private static final String BAR = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
    private static final List<String> SUBCOMMANDS = Arrays.asList("list", "info", "top", "cancel");

    private final BountyManager bountyManager;
    private final KillstreakManager ksManager;

    public BountyCommand(JavaPlugin plugin, BountyManager bountyManager, KillstreakManager ksManager) {
        super(plugin, "prime", true);
        this.bountyManager = bountyManager;
        this.ksManager = ksManager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) {
            sendHelp(player);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "list":
                doList(player);
                break;
            case "top":
                doTop(player);
                break;
            case "info":
                doInfo(player, args);
                break;
            case "cancel":
                doCancel(player);
                break;
            default:
                doPlace(player, args);
        }
    }

    // ── /prime <joueur> <montant> ─────────────────────────────────────────────

    private void doPlace(Player placer, String[] args) {
        if (args.length < 2) {
            sendHelp(placer);
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            placer.sendMessage(RC.ERR_PLAYER_NOT_FOUND);
            return;
        }
        if (target.equals(placer)) {
            placer.sendMessage(RC.BOUNTY_SELF);
            return;
        }

        Long amount = parsePositiveLong(placer, args[1]);
        if (amount == null) {
            return;
        }
        long min = bountyManager.getMinManualAmount();
        if (amount < min) {
            placer.sendMessage(Text.fmt(RC.BOUNTY_TOO_LOW, min));
            return;
        }
        if (bountyManager.getManualTarget(placer.getUniqueId()) != null) {
            placer.sendMessage(RC.BOUNTY_ALREADY_PLACED);
            return;
        }
        if (bountyManager.isTargetOnCooldown(target.getUniqueId())) {
            String remaining = Text.duration(bountyManager.targetCooldownRemaining(target.getUniqueId()));
            placer.sendMessage(Text.fmt(RC.BOUNTY_TARGET_COOLDOWN, target.getName(), remaining));
            return;
        }
        if (isFriendlyTarget(placer, target)) {
            placer.sendMessage(RC.BOUNTY_FRIENDLY_TARGET);
            return;
        }

        Economy eco = VaultEconomy.get();
        if (eco != null && eco.getBalance(placer) < amount) {
            placer.sendMessage(RC.ERR_NO_MONEY);
            return;
        }

        bountyManager.placeManualBounty(placer.getUniqueId(), target, amount, eco);
        placer.sendMessage(Text.fmt(RC.BOUNTY_PLACED, target.getName(), amount));
        Bukkit.broadcastMessage(Text.fmt(RC.BOUNTY_BROADCAST, placer.getName(), amount, target.getName()));
    }

    // ── /prime cancel ─────────────────────────────────────────────────────────

    private void doCancel(Player placer) {
        UUID target = bountyManager.getManualTarget(placer.getUniqueId());
        if (target == null) {
            placer.sendMessage(RC.PRE + "§cVous n'avez aucune prime manuelle active à annuler.");
            return;
        }
        BountyInfo info = bountyManager.getBounty(target);
        String targetName = info != null ? info.getTargetName() : RC.BOUNTY_UNKNOWN;
        long refund = bountyManager.cancelManualBounty(placer.getUniqueId(), VaultEconomy.get());
        if (refund < 0) {
            placer.sendMessage(RC.ERR_INTERNAL);
            return;
        }
        placer.sendMessage(Text.fmt(RC.BOUNTY_CANCELLED, targetName, refund));
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
                player.sendMessage("  §8┃ §c§l" + info.getTargetName()
                        + " §8| §f§l" + info.getAmount() + "$ §8| §7killstreak : §e"
                        + ksManager.getStreak(info.getTarget()));
            }
        }
        player.sendMessage(BAR);
    }

    // ── /prime info [joueur] ──────────────────────────────────────────────────

    private void doInfo(Player player, String[] args) {
        UUID targetUuid = player.getUniqueId();
        String targetName = player.getName();
        if (args.length >= 2) {
            Player target = findOnline(player, args[1]);
            if (target == null) {
                return;
            }
            targetUuid = target.getUniqueId();
            targetName = target.getName();
        }

        int streak = ksManager.getStreak(targetUuid);
        BountyInfo info = bountyManager.getBounty(targetUuid);

        player.sendMessage(BAR);
        player.sendMessage("  §e§lInfos — §f" + targetName);
        player.sendMessage("  §8┃ §7Killstreak actuel : §e§l" + streak);
        if (info != null) {
            player.sendMessage("  §8┃ §7Prime active : §f§l" + info.getAmount() + "$");
            player.sendMessage("  §8┃ §7Killstreak à la création : §c" + info.getKillstreakAtCreation());
        } else {
            player.sendMessage("  §8┃ §7Aucune prime active sur ce joueur.");
        }
        int next = nextThreshold(ksManager.getBountyThresholdKills(), streak);
        if (next > 0) {
            player.sendMessage("  §8┃ §7Prochain seuil : §c" + next + " kills §8(§7encore §c" + (next - streak) + "§8)");
        }
        player.sendMessage(BAR);
    }

    // ── /prime top ────────────────────────────────────────────────────────────

    private void doTop(Player player) {
        Map<UUID, BountyInfo> bounties = bountyManager.getActiveBounties();
        player.sendMessage(BAR);
        player.sendMessage("  §e§l🏆 PRIMES ACTIVES — SESSION");
        if (bounties.isEmpty()) {
            player.sendMessage("  §8┃ §7Aucune prime active pour le moment.");
        } else {
            List<BountyInfo> sorted = new ArrayList<>(bounties.values());
            sorted.sort((a, b) -> Long.compare(b.getAmount(), a.getAmount()));
            int rank = 1;
            for (BountyInfo bounty : sorted) {
                player.sendMessage("  §8┃ §7#" + rank++ + " §f" + bounty.getTargetName()
                        + " §8— §c" + bounty.getAmount() + "$ §8| §e"
                        + ksManager.getStreak(bounty.getTarget()) + " kills");
            }
        }
        player.sendMessage(BAR);
    }

    private void sendHelp(Player player) {
        player.sendMessage(BAR);
        player.sendMessage("  §e§l⚔ Commandes Primes & Killstreaks");
        player.sendMessage("  §8┃ §f/prime <joueur> <montant>  §8— §7Poser une prime manuelle");
        player.sendMessage("  §8┃ §f/prime cancel              §8— §7Annuler votre prime active");
        player.sendMessage("  §8┃ §f/prime list                §8— §7Primes actives");
        player.sendMessage("  §8┃ §f/prime info [joueur]       §8— §7Killstreak & prime d'un joueur");
        player.sendMessage("  §8┃ §f/prime top                 §8— §7Primes les plus élevées");
        player.sendMessage("  §8┃ §7Restrictions : faction, alliés, amis exclus. Cooldown 24h/cible.");
        player.sendMessage(BAR);
    }

    /** @return true si la cible est un ami ou en relation faction amicale (même faction, allié, trêve). */
    private boolean isFriendlyTarget(Player requester, Player target) {
        FriendManager friends = FriendManager.getInstance();
        if (friends != null && friends.areFriends(requester.getUniqueId(), target.getUniqueId())) {
            return true;
        }
        if (!FactionHook.isEnabled()) {
            return false;
        }
        try {
            if (!RedFactionAPI.isAvailable()) {
                return false;
            }
            RedFactionAPI api = RedFactionAPI.get();
            Faction requesterFaction = api.getPlayerFaction(requester);
            Faction targetFaction = api.getPlayerFaction(target);
            if (requesterFaction == null || targetFaction == null) {
                return false;
            }
            Relation relation = api.getRelation(requesterFaction, targetFaction);
            return relation == Relation.SELF || relation == Relation.ALLY || relation == Relation.TRUCE;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    completions.add(sub);
                }
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info")) {
                String prefix = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(p.getName());
                    }
                }
            } else if (!SUBCOMMANDS.contains(args[0].toLowerCase())) {
                completions.addAll(Arrays.asList("100", "500", "1000", "5000"));
            }
        }
        return completions;
    }

    private static int nextThreshold(List<Integer> thresholds, int current) {
        for (int threshold : thresholds) {
            if (threshold > current) {
                return threshold;
            }
        }
        return -1;
    }
}
