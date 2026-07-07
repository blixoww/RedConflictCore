package fr.originsfight.essentials.command.economy;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
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
 * /money [joueur] — consulte son solde, ou celui d'un autre joueur
 * (même hors ligne) avec la sous-permission {@code redconflict.money.others}.
 */
public class MoneyCommand extends EssCommand {

    private final EconomyService economy;
    private final SeenService seen;

    public MoneyCommand(CommandEnvironment env, EconomyService economy, SeenService seen) {
        super(env, "money", false, false);
        this.economy = economy;
        this.seen = seen;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!economy.isAvailable()) {
            sender.sendMessage(Text.error("L'économie est indisponible sur ce serveur."));
            return false;
        }

        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.money")) return false;
            UUID targetId = seen.resolveUuid(args[0]);
            if (targetId == null) {
                sender.sendMessage(Text.error("Joueur inconnu : §f" + args[0]));
                return false;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            String name = target.getName() != null ? target.getName() : args[0];
            sender.sendMessage(Text.info("Solde de §f" + name + " §7: §f"
                    + economy.format(economy.getBalance(target))));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.ERR_PLAYER_ONLY);
            return false;
        }
        Player player = (Player) sender;
        player.sendMessage(Text.info("Votre solde : §f" + economy.format(economy.getBalance(player))));
        return true;
    }
}
