package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.WarpService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * /delwarp <nom> — supprime un warp (admin).
 */
public class DelWarpCommand extends EssCommand {

    private final WarpService warps;

    public DelWarpCommand(CommandEnvironment env, WarpService warps) {
        super(env, "delwarp", false, false);
        this.warps = warps;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Text.error("Usage : /delwarp <nom>"));
            return false;
        }
        if (warps.delete(args[0])) {
            sender.sendMessage(Text.success("Warp §f" + WarpService.normalize(args[0]) + " §asupprimé."));
            return true;
        }
        sender.sendMessage(Text.error("Warp §f" + WarpService.normalize(args[0]) + " §cintrouvable."));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String name : warps.names()) {
                if (name.startsWith(prefix)) matches.add(name);
            }
        }
        return matches;
    }
}
