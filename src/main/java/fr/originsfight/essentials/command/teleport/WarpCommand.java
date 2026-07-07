package fr.originsfight.essentials.command.teleport;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.TeleportService;
import fr.originsfight.essentials.service.WarpService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /warp [nom] — téléportation à un warp public (liste les warps sans argument).
 * Si warps.per-warp-permission est actif, exige redconflict.warp.<nom>.
 */
public class WarpCommand extends EssCommand {

    private final WarpService warps;
    private final TeleportService teleports;

    public WarpCommand(CommandEnvironment env, WarpService warps, TeleportService teleports) {
        super(env, "warp", true, false); // cooldown armé par le TeleportService à l'arrivée
        this.warps = warps;
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (args.length < 1) {
            List<String> names = warps.names();
            if (names.isEmpty()) {
                player.sendMessage(Text.info("Aucun warp n'est défini sur ce serveur."));
            } else {
                player.sendMessage(Text.info("Warps disponibles (§f" + names.size() + "§7) : §f"
                        + String.join("§7, §f", names)));
            }
            return false;
        }

        String name = WarpService.normalize(args[0]);
        if (env.getConfig().perWarpPermission() && !player.hasPermission("redconflict.warp." + name)) {
            player.sendMessage(Text.error("Vous n'avez pas accès au warp §f" + name + "§c."));
            return false;
        }
        Location destination = warps.find(name);
        if (destination == null) {
            player.sendMessage(Text.error("Warp §f" + name + " §cintrouvable."));
            return false;
        }
        final Location target = destination;
        teleports.delayedTeleport(player, () -> target, "warp");
        return true;
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
