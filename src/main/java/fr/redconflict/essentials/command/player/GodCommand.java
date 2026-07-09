package fr.redconflict.essentials.command.player;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.Messages;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.PlayerStateService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /god [joueur] — mode invincible (toggle). Les dégâts de toutes sources
 * sont annulés par {@code GodListener}, la faim est gelée.
 */
public class GodCommand extends EssCommand {

    private final PlayerStateService states;

    public GodCommand(CommandEnvironment env, PlayerStateService states) {
        super(env, "god", false, false);
        this.states = states;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.god")) return false;
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        boolean enabled = states.toggleGod(target);
        String state = enabled ? "§aactivé" : "§cdésactivé";
        target.sendMessage(Text.info("Mode dieu " + state + "§7."));
        if (sender != target) {
            sender.sendMessage(Text.info("Mode dieu de §f" + target.getName() + " §7" + state + "§7."));
        }
        return true;
    }
}
