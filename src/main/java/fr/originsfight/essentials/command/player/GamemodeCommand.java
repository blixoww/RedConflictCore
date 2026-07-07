package fr.originsfight.essentials.command.player;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /gm &lt;mode&gt; [joueur] — change de gamemode. Accepte les numéros (0-3),
 * les noms complets et les abréviations (s, c, a, sp).
 */
public class GamemodeCommand extends EssCommand {

    public GamemodeCommand(CommandEnvironment env) {
        super(env, "gm", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Text.error("Usage : /gm <0|1|2|3|survival|creative|adventure|spectator> [joueur]"));
            return false;
        }

        GameMode mode = parseMode(args[0]);
        if (mode == null) {
            sender.sendMessage(Text.error("Mode de jeu inconnu : §f" + args[0]));
            return false;
        }

        Player target;
        if (args.length >= 2) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.gm")) return false;
            target = findOnline(sender, args[1]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        target.setGameMode(mode);
        String name = mode.name().toLowerCase(Locale.ROOT);
        target.sendMessage(Text.success("Mode de jeu : §f" + name + "§a."));
        if (sender != target) {
            sender.sendMessage(Text.success("Mode de jeu de §f" + target.getName() + " §a: §f" + name + "§a."));
        }
        return true;
    }

    private GameMode parseMode(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "0": case "s": case "survival": case "survie":
                return GameMode.SURVIVAL;
            case "1": case "c": case "creative": case "creatif": case "créatif":
                return GameMode.CREATIVE;
            case "2": case "a": case "adventure": case "aventure":
                return GameMode.ADVENTURE;
            case "3": case "sp": case "spectator": case "spectateur":
                return GameMode.SPECTATOR;
            default:
                return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String mode : Arrays.asList("survival", "creative", "adventure", "spectator")) {
                if (mode.startsWith(args[0].toLowerCase(Locale.ROOT))) matches.add(mode);
            }
            return matches;
        }
        return null; // complétion joueurs standard
    }
}
