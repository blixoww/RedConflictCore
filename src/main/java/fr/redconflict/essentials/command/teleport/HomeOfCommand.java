package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.model.StoredLocation;
import fr.redconflict.essentials.service.HomeService;
import fr.redconflict.essentials.service.SeenService;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /homes &lt;joueur&gt; [nom] — consulte les homes d'un joueur, et s'y téléporte.
 *
 * <p>Sans nom de home : liste ceux du joueur visé, avec leur monde et leurs
 * coordonnées. Avec un nom : téléporte immédiatement dessus.
 *
 * <p><b>Téléportation immédiate, sans délai d'attente.</b> Le délai de /home
 * existe pour empêcher un joueur de fuir un combat ; un membre du staff qui
 * enquête sur une base n'a pas à attendre trois secondes, et n'a de toute façon
 * pas d'adversaire à fuir. Le point de départ est enregistré pour /back, ce qui
 * permet de revenir d'un geste.
 *
 * <p>Fonctionne sur les joueurs hors ligne : le nom est résolu via
 * {@link SeenService}, qui connaît le dernier passage de chacun.
 */
public class HomeOfCommand extends EssCommand {

    /** Permission déclarée dans plugin.yml, vérifiée par Bukkit avant l'exécuteur. */
    private final HomeService homes;
    private final SeenService seen;
    private final TeleportService teleports;

    public HomeOfCommand(CommandEnvironment env, HomeService homes, SeenService seen,
                         TeleportService teleports) {
        super(env, "homes", true, true);
        this.homes = homes;
        this.seen = seen;
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player staff = (Player) sender;

        if (args.length < 1) {
            staff.sendMessage(Text.error("Usage : §f/homes <joueur> [nom]"));
            return false;
        }
        UUID target = seen.resolveUuid(args[0]);
        if (target == null) {
            staff.sendMessage(Text.error("Joueur §f" + args[0] + " §cinconnu."));
            return false;
        }

        Map<String, StoredLocation> all = homes.list(target);
        if (all.isEmpty()) {
            staff.sendMessage(Text.info("§f" + args[0] + " §7n'a aucun home."));
            return false;
        }

        if (args.length == 1) {
            listHomes(staff, args[0], all);
            return false; // consultation : pas de cooldown à armer
        }

        String name = args[1];
        Location destination = homes.find(target, name);
        if (destination == null) {
            staff.sendMessage(Text.error("Home §f" + HomeService.normalize(name)
                    + " §cintrouvable chez §f" + args[0] + "§c."));
            return false;
        }
        teleports.teleportNow(staff, destination);
        staff.sendMessage(Text.info("Téléporté au home §f" + HomeService.normalize(name)
                + " §7de §f" + args[0] + "§7. §8(/back pour revenir)"));
        return true;
    }

    /** Liste avec monde et coordonnées : de quoi juger sans se déplacer. */
    private void listHomes(Player staff, String owner, Map<String, StoredLocation> all) {
        staff.sendMessage(Text.info("Homes de §f" + owner + " §7(§f" + all.size() + "§7) :"));
        for (Map.Entry<String, StoredLocation> entry : all.entrySet()) {
            StoredLocation location = entry.getValue();
            staff.sendMessage("§8 • §f" + entry.getKey() + " §7— §f" + location.getWorldName()
                    + " §8[§7" + Math.round(location.getX())
                    + "§8, §7" + Math.round(location.getY())
                    + "§8, §7" + Math.round(location.getZ()) + "§8]");
        }
        staff.sendMessage("§8   /homes " + owner + " <nom> §7pour s'y téléporter.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<String>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(prefix)) {
                    matches.add(online.getName());
                }
            }
        } else if (args.length == 2) {
            UUID target = seen.resolveUuid(args[0]);
            if (target != null) {
                String prefix = args[1].toLowerCase();
                for (String name : homes.list(target).keySet()) {
                    if (name.startsWith(prefix)) {
                        matches.add(name);
                    }
                }
            }
        }
        return matches;
    }
}
