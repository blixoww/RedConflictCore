package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import fr.originsfight.friend.FriendManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Commande /prime (alias /bounty).
 *
 *   /prime <joueur> <montant>  – poser une prime manuelle
 *   /prime cancel              – annuler sa prime manuelle active
 *   /prime list                – primes actives
 *   /prime info [joueur]       – killstreak & prime d'un joueur
 *   /prime top                 – top primes de la session
 *
 * Restrictions :
 *   - Impossible de cibler un membre de sa faction, un allié ou un ami.
 *   - Délai 24h entre deux primes sur le même joueur (anti-harcèlement).
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private static final String BAR = "§8§m━━━━━━��━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

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

        if (args.length == 0) { sendHelp(player); return true; }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list":   doList(player);              break;
            case "top":    doTop(player);               break;
            case "info":   doInfo(player, args);        break;
            case "cancel": doCancel(player);            break;
            default:       doPlace(player, args);       break; // /prime <joueur> <montant>
        }
        return true;
    }

    // ── /prime <joueur> <montant> ─────────────────────────────────────────────

    private void doPlace(Player placer, String[] args) {
        if (args.length < 2) { sendHelp(placer); return; }

        // Trouver la cible
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) { placer.sendMessage(RC.BOUNTY_NOT_FOUND); return; }
        if (target.equals(placer)) { placer.sendMessage(RC.BOUNTY_SELF); return; }

        // Montant
        long amount;
        try { amount = Long.parseLong(args[1]); } catch (NumberFormatException e) {
            placer.sendMessage(RC.BOUNTY_INVALID_AMOUNT); return;
        }
        if (amount <= 0) { placer.sendMessage(RC.BOUNTY_INVALID_AMOUNT); return; }
        long min = bountyManager.getMinManualAmount();
        if (amount < min) { placer.sendMessage(RC.fmt(RC.BOUNTY_TOO_LOW, min)); return; }

        // Déjà une prime manuelle active
        if (bountyManager.getManualTarget(placer.getUniqueId()) != null) {
            placer.sendMessage(RC.BOUNTY_ALREADY_PLACED); return;
        }

        // Cooldown 24h sur la cible
        if (bountyManager.isTargetOnCooldown(target.getUniqueId())) {
            String remaining = formatDuration(bountyManager.targetCooldownRemaining(target.getUniqueId()));
            placer.sendMessage(RC.fmt(RC.BOUNTY_TARGET_COOLDOWN, target.getName(), remaining)); return;
        }

        // Vérification faction / ami
        if (isFriendlyTarget(placer, target)) {
            placer.sendMessage(RC.BOUNTY_FRIENDLY_TARGET); return;
        }

        // Fonds suffisants
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        if (eco != null && eco.getBalance(placer) < amount) {
            placer.sendMessage(RC.BOUNTY_NO_MONEY); return;
        }

        // Placement
        bountyManager.placeManualBounty(placer.getUniqueId(), target, amount, eco);
        placer.sendMessage(RC.fmt(RC.BOUNTY_PLACED, target.getName(), amount));
        Bukkit.broadcastMessage(RC.fmt(RC.BOUNTY_BROADCAST, placer.getName(), amount, target.getName()));
    }

    // ── /prime cancel ─────────────────────────────────────────────────────────

    private void doCancel(Player placer) {
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        UUID target = bountyManager.getManualTarget(placer.getUniqueId());
        if (target == null) {
            placer.sendMessage(RC.PRE + "§cVous n'avez aucune prime manuelle active à annuler.");
            return;
        }
        String targetName = bountyManager.getBounty(target) != null
                ? bountyManager.getBounty(target).getTargetName() : "Inconnu";
        long refund = bountyManager.cancelManualBounty(placer.getUniqueId(), eco);
        if (refund < 0) {
            placer.sendMessage(RC.PRE + "§cErreur lors de l'annulation."); return;
        }
        placer.sendMessage(RC.fmt(RC.BOUNTY_CANCELLED, targetName, refund));
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
        Map<UUID, BountyInfo> bounties = bountyManager.getActiveBounties();
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
        player.sendMessage("  §8┃ §f/prime <joueur> <montant>  §8— §7Poser une prime manuelle");
        player.sendMessage("  §8┃ §f/prime cancel              §8— §7Annuler votre prime active");
        player.sendMessage("  §8┃ §f/prime list                §8— §7Primes actives");
        player.sendMessage("  §8┃ §f/prime info [joueur]       §8— §7Killstreak & prime d'un joueur");
        player.sendMessage("  §8┃ §f/prime top                 §8— §7Primes les plus élevées");
        player.sendMessage("  §8┃ §7Restrictions : faction, alliés, amis exclus. Cooldown 24h/cible.");
        player.sendMessage(BAR);
    }

    // ── Vérifie si la cible est amie / même faction / alliée ─────────────────

    /**
     * Retourne true si la cible est un ami ou dans une faction amicale (own/ally/truce).
     */
    private boolean isFriendlyTarget(Player requester, Player target) {
        // Vérif ami
        FriendManager fm = FriendManager.getInstance();
        if (fm != null && fm.areFriends(requester.getUniqueId(), target.getUniqueId())) return true;

        // Vérif faction via l'API RedFaction
        try {
            if (!fr.redfaction.api.RedFactionAPI.isAvailable()) return false;
            fr.redfaction.api.RedFactionAPI api = fr.redfaction.api.RedFactionAPI.get();
            fr.redfaction.entity.Faction fRequester = api.getPlayerFaction(requester);
            fr.redfaction.entity.Faction fTarget    = api.getPlayerFaction(target);
            if (fRequester == null || fTarget == null) return false;

            fr.redfaction.entity.Relation rel = api.getRelation(fRequester, fTarget);
            if (rel == null) return false;
            // SELF = même faction, ALLY = allié, TRUCE = trêve → tous considérés amicaux
            return rel == fr.redfaction.entity.Relation.SELF
                || rel == fr.redfaction.entity.Relation.ALLY
                || rel == fr.redfaction.entity.Relation.TRUCE;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String s : Arrays.asList("list", "info", "top", "cancel")) {
                if (s.startsWith(prefix)) completions.add(s);
            }
            // Complétion joueurs pour /prime <joueur>
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) completions.add(p.getName());
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info")) {
                String prefix = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(prefix)) completions.add(p.getName());
                }
            }
            // Pour /prime <joueur> <montant> : suggestions de montants
            if (!Arrays.asList("list", "top", "cancel", "info").contains(args[0].toLowerCase())) {
                completions.addAll(Arrays.asList("100", "500", "1000", "5000"));
            }
        }
        return completions;
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private static int nextThreshold(List<Integer> thresholds, int current) {
        for (int t : thresholds) if (t > current) return t;
        return -1;
    }

    /** Formate une durée en ms en "Xh Ym" lisible. */
    private static String formatDuration(long ms) {
        long hours   = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        if (hours > 0) return hours + "h " + minutes + "min";
        return minutes + "min";
    }
}
