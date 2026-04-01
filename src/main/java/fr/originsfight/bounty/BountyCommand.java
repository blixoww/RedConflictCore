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

import java.util.ArrayList;
import java.util.List;

/**
 * Commande /prime (alias /bounty) : place une prime sur un joueur.
 *
 * Usage : /prime <joueur> <montant>
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

        if (args.length < 2) {
            player.sendMessage(RC.BOUNTY_USAGE);
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(RC.BOUNTY_NOT_FOUND);
            return true;
        }

        if (target.equals(player)) {
            player.sendMessage(RC.BOUNTY_SELF);
            return true;
        }

        // Parse du montant
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(RC.BOUNTY_INVALID_AMOUNT);
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(RC.BOUNTY_INVALID_AMOUNT);
            return true;
        }

        // Vérifier que le joueur n'a pas déjà placé une prime
        if (manager.hasPlacedBounty(player.getUniqueId())) {
            player.sendMessage(RC.BOUNTY_ALREADY_PLACED);
            return true;
        }

        // Vérifier que la cible n'a pas déjà une prime
        if (manager.hasBounty(target.getUniqueId())) {
            player.sendMessage(RC.BOUNTY_ALREADY_TARGET);
            return true;
        }

        // Vérifier les fonds via Vault
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        if (eco == null) {
            player.sendMessage(RC.BOUNTY_ECO_ERROR);
            return true;
        }

        if ((long) eco.getBalance(player) < amount) {
            player.sendMessage(RC.BOUNTY_NO_MONEY);
            return true;
        }

        // Débiter et placer la prime
        eco.withdrawPlayer(player, amount);
        manager.placeBounty(player.getUniqueId(), player.getName(),
                target.getUniqueId(), target.getName(), amount);

        // Message de confirmation au commanditaire
        player.sendMessage(RC.fmt(RC.BOUNTY_PLACED, target.getName(), amount));

        // Annonce globale
        for (String line : RC.fmt(RC.BOUNTY_BROADCAST, player.getName(), amount, target.getName()).split("\n")) {
            Bukkit.broadcastMessage(line);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
