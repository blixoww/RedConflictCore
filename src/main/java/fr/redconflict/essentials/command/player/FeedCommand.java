package fr.redconflict.essentials.command.player;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.Messages;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /feed [joueur] — recharge la faim (et la saturation, comme Essentials).
 */
public class FeedCommand extends EssCommand {

    public FeedCommand(CommandEnvironment env) {
        super(env, "feed", false, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.feed")) return false;
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        target.setFoodLevel(20);
        target.setSaturation(10f);
        target.setExhaustion(0f);

        if (sender == target) {
            target.sendMessage(Text.success("Votre faim a été rechargée."));
        } else {
            sender.sendMessage(Text.success("Faim de §f" + target.getName() + " §arechargée."));
            target.sendMessage(Text.success("Votre faim a été rechargée."));
        }
        return true;
    }
}
