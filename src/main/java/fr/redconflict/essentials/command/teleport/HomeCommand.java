package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.model.StoredLocation;
import fr.redconflict.essentials.service.HomeService;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /home [nom] — téléportation à un home. Sans argument : téléporte au home
 * unique, ou liste les homes s'il y en a plusieurs.
 */
public class HomeCommand extends EssCommand {

    private final HomeService homes;
    private final TeleportService teleports;

    public HomeCommand(CommandEnvironment env, HomeService homes, TeleportService teleports) {
        super(env, "home", true, false); // cooldown armé par le TeleportService à l'arrivée
        this.homes = homes;
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        String name;
        if (args.length >= 1) {
            name = args[0];
        } else {
            Map<String, StoredLocation> all = homes.list(player.getUniqueId());
            if (all.isEmpty()) {
                player.sendMessage(Text.error("Vous n'avez aucun home. Utilisez §f/sethome§c."));
                return false;
            }
            if (all.size() > 1) {
                player.sendMessage(Text.info("Vos homes (§f" + all.size() + "§7) : §f"
                        + String.join("§7, §f", all.keySet())));
                return false;
            }
            name = all.keySet().iterator().next();
        }

        Location destination = homes.find(player.getUniqueId(), name);
        if (destination == null) {
            player.sendMessage(Text.error("Home §f" + HomeService.normalize(name) + " §cintrouvable."));
            return false;
        }
        final Location target = destination;
        teleports.delayedTeleport(player, () -> target, "home");
        return true;
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
