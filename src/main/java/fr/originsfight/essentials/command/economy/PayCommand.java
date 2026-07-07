package fr.originsfight.essentials.command.economy;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.EconomyService;
import fr.originsfight.essentials.service.SeenService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * /pay &lt;joueur&gt; &lt;montant&gt; — paie un joueur, y compris hors ligne
 * (résolution du nom via l'historique /seen).
 */
public class PayCommand extends EssCommand {

    private final EconomyService economy;
    private final SeenService seen;

    public PayCommand(CommandEnvironment env, EconomyService economy, SeenService seen) {
        super(env, "pay", true, true);
        this.economy = economy;
        this.seen = seen;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 2) {
            player.sendMessage(Text.error("Usage : /pay <joueur> <montant>"));
            return false;
        }
        if (!economy.isAvailable()) {
            player.sendMessage(Text.error("L'économie est indisponible sur ce serveur."));
            return false;
        }

        UUID targetId = seen.resolveUuid(args[0]);
        if (targetId == null) {
            player.sendMessage(Text.error("Joueur inconnu : §f" + args[0]));
            return false;
        }
        if (targetId.equals(player.getUniqueId())) {
            player.sendMessage(Text.error("Vous ne pouvez pas vous payer vous-même."));
            return false;
        }

        Double amount = parseAmount(player, args[1]);
        if (amount == null) return false;

        if (!economy.has(player, amount)) {
            player.sendMessage(Text.error("Solde insuffisant (§f" + economy.format(economy.getBalance(player)) + "§c)."));
            return false;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        if (!economy.withdraw(player, amount)) {
            player.sendMessage(Text.error("Le paiement a échoué."));
            return false;
        }
        if (!economy.deposit(target, amount)) {
            economy.deposit(player, amount); // remboursement, jamais d'argent détruit
            player.sendMessage(Text.error("Le paiement a échoué."));
            return false;
        }

        String formatted = economy.format(amount);
        String targetName = target.getName() != null ? target.getName() : args[0];
        player.sendMessage(Text.success("Vous avez payé §f" + formatted + " §aà §f" + targetName + "§a."));
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Text.success("§f" + player.getName() + " §avous a payé §f" + formatted + "§a."));
        }
        return true;
    }
}
