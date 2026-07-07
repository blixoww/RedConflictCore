package fr.originsfight.essentials.command.teleport;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /top — téléportation au point le plus haut de la colonne où l'on se trouve.
 */
public class TopCommand extends EssCommand {

    private final TeleportService teleports;

    public TopCommand(CommandEnvironment env, TeleportService teleports) {
        super(env, "top", true, false); // cooldown armé par le TeleportService à l'arrivée
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        final Player player = (Player) sender;
        teleports.delayedTeleport(player, () -> {
            Location current = player.getLocation();
            int highestY = player.getWorld().getHighestBlockYAt(current);
            return new Location(current.getWorld(), current.getX(), highestY + 1.0,
                    current.getZ(), current.getYaw(), current.getPitch());
        }, "top");
        return true;
    }
}
