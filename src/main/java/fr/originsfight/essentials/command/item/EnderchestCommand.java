package fr.originsfight.essentials.command.item;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.core.text.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /ec [joueur] — ouvre son enderchest ; celui d'un autre joueur avec
 * la sous-permission {@code redconflict.ec.others}.
 */
public class EnderchestCommand extends EssCommand {

    public EnderchestCommand(CommandEnvironment env) {
        super(env, "ec", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length >= 1) {
            if (!checkOthers(player, "redconflict.ec")) return false;
            Player target = findOnline(player, args[0]);
            if (target == null) return false;
            player.openInventory(target.getEnderChest());
            player.sendMessage(Text.info("Enderchest de §f" + target.getName() + "§7."));
            return true;
        }
        player.openInventory(player.getEnderChest());
        return true;
    }
}
