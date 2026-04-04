package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Commande /prime (alias /bounty) : place une prime sur un joueur.
 *
 * Sous-commandes :
 *   /prime <joueur> <montant>  – placer une prime
 *   /prime list                – liste des primes actives
 *   /prime info [joueur]       – détail d'une prime
 *   /prime cancel              – annuler sa propre prime (remboursement, cooldown 5 min)
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private final BountyManager manager;

    public BountyCommand(BountyManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "list":
                doList(player);
                return true;
            case "info":
                doInfo(player, args);
                return true;
            case "cancel":
                doCancel(player);
                return true;
            default:
                // /prime <joueur> <montant>
                doPlace(player, args);
                return true;
        }
    }

    // ── /prime <joueur> <montant> ─────────────────────────────────────────────

    private void doPlace(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(RC.BOUNTY_USAGE);
            return;
        }

        // Résoudre la cible (peut être hors ligne → on utilise le nom pour retrouver l'UUID via lastKiller)
        UUID lastKillerUuid = manager.getLastKiller(player.getUniqueId());
        if (lastKillerUuid == null) {
            player.sendMessage(RC.PRE + "§cVous ne pouvez placer une prime que sur votre dernier tueur. §7Vous n'en avez pas encore.");
            return;
        }

        // Vérifier que le joueur cible correspond bien au dernier tueur
        String targetName = args[0];
        Player targetOnline = Bukkit.getPlayer(targetName);

        UUID targetUuid;
        String resolvedName;

        if (targetOnline != null) {
            targetUuid    = targetOnline.getUniqueId();
            resolvedName  = targetOnline.getName();
        } else {
            // Cible hors ligne : on accepte uniquement si l'UUID stocké correspond
            // On vérifie par le nom (insensible à la casse) en comparant avec les données du dernier tueur
            // Le lastKiller stocke l'UUID ; on récupère le nom depuis la BountyInfo ou les données KS
            // Ici on force : si hors-ligne, le joueur doit connaître le nom exact
            player.sendMessage(RC.BOUNTY_NOT_FOUND);
            return;
        }

        if (targetUuid.equals(player.getUniqueId())) {
            player.sendMessage(RC.BOUNTY_SELF);
            return;
        }

        // Vérification : cible = dernier tueur
        if (!targetUuid.equals(lastKillerUuid)) {
            // Chercher le nom du dernier tueur pour le message
            Player lastKillerPlayer = Bukkit.getPlayer(lastKillerUuid);
            String killerName = lastKillerPlayer != null ? lastKillerPlayer.getName() : "Inconnu";
            player.sendMessage(String.format(RC.PRE + "§cVous ne pouvez cibler que votre dernier tueur : §f%s§c.", killerName));
            return;
        }

        // Parse du montant
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(RC.BOUNTY_INVALID_AMOUNT);
            return;
        }

        if (amount < manager.getMinimumAmount()) {
            player.sendMessage(String.format(RC.PRE + "§cMontant minimum : §f%d$§c.", manager.getMinimumAmount()));
            return;
        }

        // Vérifier que le joueur n'a pas déjà placé une prime
        if (manager.hasPlacedBounty(player.getUniqueId())) {
            player.sendMessage(RC.BOUNTY_ALREADY_PLACED);
            return;
        }

        // Vérifier que la cible n'a pas déjà une prime
        if (manager.hasBounty(targetUuid)) {
            player.sendMessage(RC.BOUNTY_ALREADY_TARGET);
            return;
        }

        // Vérifier les fonds via Vault
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        if (eco == null) {
            player.sendMessage(RC.BOUNTY_ECO_ERROR);
            return;
        }
        if ((long) eco.getBalance(player) < amount) {
            player.sendMessage(RC.BOUNTY_NO_MONEY);
            return;
        }

        // Débiter et placer la prime
        eco.withdrawPlayer(player, amount);
        manager.placeBounty(player.getUniqueId(), player.getName(), targetUuid, resolvedName, amount);

        // Confirmation
        player.sendMessage(RC.fmt(RC.BOUNTY_PLACED, resolvedName, amount));

        // Annonce globale
        for (String line : RC.fmt(RC.BOUNTY_BROADCAST, player.getName(), amount, resolvedName).split("\n")) {
            Bukkit.broadcastMessage(line);
        }
    }

    // ── /prime list ───────────────────────────────────────────────────────────

    private void doList(Player player) {
        Map<UUID, BountyInfo> all = manager.getBounties();
        if (all.isEmpty()) {
            player.sendMessage(RC.PRE + "§7Aucune prime active pour le moment.");
            return;
        }
        player.sendMessage(RC.SEP);
        player.sendMessage(RC.PRE + "§e§lPrimes actives §8(" + all.size() + ") §7:");
        for (BountyInfo info : all.values()) {
            long remaining = info.getRemainingMs(BountyManager.BOUNTY_DURATION_MS);
            String time = formatDuration(Math.max(0, remaining));
            player.sendMessage(RC.PRE_S + "§c" + info.getTargetName()
                + " §8| §fpar §7" + info.getSetterName()
                + " §8| §f" + info.getAmount() + "$ §8| §7expire dans §f" + time);
        }
        player.sendMessage(RC.SEP);
    }

    // ── /prime info [joueur] ──────────────────────────────────────────────────

    private void doInfo(Player player, String[] args) {
        BountyInfo info;
        if (args.length >= 2) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(RC.BOUNTY_NOT_FOUND);
                return;
            }
            info = manager.getBounty(target.getUniqueId());
        } else {
            info = manager.getBounty(player.getUniqueId());
        }

        if (info == null) {
            player.sendMessage(RC.PRE + "§7Aucune prime sur ce joueur.");
            return;
        }

        long remaining = info.getRemainingMs(BountyManager.BOUNTY_DURATION_MS);
        player.sendMessage(RC.SEP);
        player.sendMessage(RC.PRE + "§e§lPrime sur §c" + info.getTargetName());
        player.sendMessage(RC.PRE_S + "§7Posée par : §f" + info.getSetterName());
        player.sendMessage(RC.PRE_S + "§7Montant   : §f" + info.getAmount() + "$");
        player.sendMessage(RC.PRE_S + "§7Expire dans : §f" + formatDuration(Math.max(0, remaining)));
        player.sendMessage(RC.SEP);
    }

    // ── /prime cancel ─────────────────────────────────────────────────────────

    private void doCancel(Player player) {
        if (!manager.hasPlacedBounty(player.getUniqueId())) {
            player.sendMessage(RC.PRE + "§7Vous n'avez aucune prime active à annuler.");
            return;
        }
        // Trouver la bounty placée par ce joueur
        UUID targetUuid = null;
        for (Map.Entry<UUID, BountyInfo> entry : manager.getBounties().entrySet()) {
            if (entry.getValue().getSetter().equals(player.getUniqueId())) {
                targetUuid = entry.getKey();
                break;
            }
        }
        if (targetUuid == null) {
            player.sendMessage(RC.PRE + "§cErreur interne — prime introuvable.");
            return;
        }
        BountyInfo info = manager.removeBounty(targetUuid);
        if (info != null) {
            Economy eco = OriginsFightCore.getInstance().getEconomy();
            if (eco != null) eco.depositPlayer(player, info.getAmount());
            player.sendMessage(String.format(RC.PRE + "§ePrime sur §f%s §eannulée. §f+%d$ §eremboursé.", info.getTargetName(), info.getAmount()));
        }
    }

    // ── Aide ──────────────────────────────────────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage(RC.SEP);
        player.sendMessage(RC.PRE + "§e§lCommandes Primes");
        player.sendMessage(RC.PRE_S + "§f/prime <joueur> <montant> §8— §7Mettre une prime sur votre tueur");
        player.sendMessage(RC.PRE_S + "§f/prime list §8— §7Voir toutes les primes actives");
        player.sendMessage(RC.PRE_S + "§f/prime info [joueur] §8— §7Détails d'une prime");
        player.sendMessage(RC.PRE_S + "§f/prime cancel §8— §7Annuler votre prime (remboursement)");
        player.sendMessage(RC.SEP);
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        long hours   = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (hours > 0)   return hours + "h " + minutes + "min";
        if (minutes > 0) return minutes + "min " + seconds + "s";
        return seconds + "s";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : Arrays.asList("list", "info", "cancel")) {
                if (sub.startsWith(prefix)) completions.add(sub);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) completions.add(p.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            String prefix = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) completions.add(p.getName());
            }
        }
        return completions;
    }
}
