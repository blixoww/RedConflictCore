package fr.redconflict.vote;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /rcvote <pseudo>} — appelée par Azuriom via AzLink une fois le vote
 * validé. {@code /rcvote reload} recharge la table de butin, {@code info} la
 * résume.
 *
 * <p>Réservée à la console et aux administrateurs : elle donne des PB et des
 * objets, elle n'a rien à faire entre les mains d'un joueur.
 *
 * <p>Classe séparée, et non imbriquée dans le module : une classe interne ne
 * peut pas passer l'instance englobante à {@code super()}.
 */
public class VoteCommand extends CoreCommand {

    private final VoteRewards rewards;
    private final VoteStorage storage;

    public VoteCommand(RedConflictCore plugin, VoteRewards rewards, VoteStorage storage) {
        super(plugin, "rcvote", false);
        this.rewards = rewards;
        this.storage = storage;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GRAY + "Usage : /" + label
                    + " <pseudo> | reload | info | compte <pseudo> | reset <pseudo>");
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            rewards.reload();
            sender.sendMessage(ChatColor.GREEN + "[Vote] Récompenses rechargées ("
                    + rewards.nombreDeLots() + " lots).");
            return;
        }

        // Compteur de fidélité : le lire et le corriger sans ouvrir H2. Les
        // `rcvote` lancés à la main pour tester l'incrémentent comme les vrais.
        if (args[0].equalsIgnoreCase("compte") || args[0].equalsIgnoreCase("reset")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.GRAY + "Usage : /" + label + " " + args[0].toLowerCase() + " <pseudo>");
                return;
            }
            if (!storage.isAvailable()) {
                sender.sendMessage(ChatColor.RED + "Base H2 indisponible.");
                return;
            }
            @SuppressWarnings("deprecation")
            java.util.UUID uuid = org.bukkit.Bukkit.getOfflinePlayer(args[1]).getUniqueId();

            if (args[0].equalsIgnoreCase("reset")) {
                boolean fait = storage.reinitialiser(uuid);
                sender.sendMessage(fait
                        ? ChatColor.GREEN + "[Vote] Compteur de " + args[1] + " remis à zéro."
                        : ChatColor.GRAY + "[Vote] Aucun compteur pour " + args[1] + ".");
                return;
            }
            sender.sendMessage(ChatColor.GRAY + "[Vote] " + args[1] + " : "
                    + ChatColor.WHITE + storage.total(uuid) + ChatColor.GRAY + " vote(s) cumulé(s).");
            return;
        }

        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.GRAY + "[Vote] " + rewards.nombreDeLots()
                    + " lots, " + storage.enAttenteTotal() + " en attente de remise.");
            return;
        }

        String pseudo = args[0];
        if (pseudo.length() > 32) {
            sender.sendMessage(ChatColor.RED + "Pseudo invalide.");
            return;
        }

        rewards.recompenser(pseudo);
        sender.sendMessage(ChatColor.GREEN + "[Vote] " + pseudo + " récompensé.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Commande de machine : la complétion n'aide personne.
        return new ArrayList<>();
    }
}
