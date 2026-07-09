package fr.redconflict.essentials.command.player;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.Messages;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.PlayerStateService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /fly [joueur] — mode vol (toggle). La persistance à la déconnexion
 * dépend de {@code fly.persist-on-quit} dans essentials.yml.
 */
public class FlyCommand extends EssCommand {

    private final PlayerStateService states;

    public FlyCommand(CommandEnvironment env, PlayerStateService states) {
        super(env, "fly", false, false);
        this.states = states;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.fly")) return false;
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        boolean enabled = states.toggleFly(target);
        String state = enabled ? "§aactivé" : "§cdésactivé";
        target.sendMessage(Text.info("Mode vol " + state + "§7."));
        if (sender != target) {
            sender.sendMessage(Text.info("Mode vol de §f" + target.getName() + " §7" + state + "§7."));
        }
        return true;
    }
}
