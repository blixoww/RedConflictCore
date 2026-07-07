package fr.originsfight.essentials.command.teleport;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.HomeService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /delhome <nom> — supprime un home.
 */
public class DelHomeCommand extends EssCommand {

    private final HomeService homes;

    public DelHomeCommand(CommandEnvironment env, HomeService homes) {
        super(env, "delhome", true, false);
        this.homes = homes;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /delhome <nom>"));
            return false;
        }
        if (homes.delete(player.getUniqueId(), args[0])) {
            player.sendMessage(Text.success("Home §f" + HomeService.normalize(args[0]) + " §asupprimé."));
            return true;
        }
        player.sendMessage(Text.error("Home §f" + HomeService.normalize(args[0]) + " §cintrouvable."));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1 && sender instanceof Player) {
            String prefix = args[0].toLowerCase();
            for (String name : homes.list(((Player) sender).getUniqueId()).keySet()) {
                if (name.startsWith(prefix)) matches.add(name);
            }
        }
        return matches;
    }
}
