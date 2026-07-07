package fr.originsfight.essentials.command.player;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /kill [joueur] — tue un joueur (admin). Sans argument : soi-même.
 */
public class KillCommand extends EssCommand {

    public KillCommand(CommandEnvironment env) {
        super(env, "kill", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        target.setHealth(0.0);
        if (sender != target) {
            sender.sendMessage(Text.success("§f" + target.getName() + " §aa été tué."));
        }
        return true;
    }
}
