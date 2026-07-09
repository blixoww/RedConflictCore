package fr.redconflict.essentials.command.teleport;

import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.SpawnService;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /spawn — téléportation au spawn du serveur (délai d'attente + /back).
 * Retombe sur le spawn du monde si aucun spawn n'a été défini via /setspawn.
 */
public class SpawnCommand extends EssCommand {

    private final SpawnService spawnService;
    private final TeleportService teleports;

    public SpawnCommand(CommandEnvironment env, SpawnService spawnService, TeleportService teleports) {
        super(env, "spawn", true, false); // cooldown armé par le TeleportService à l'arrivée
        this.spawnService = spawnService;
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        final Player player = (Player) sender;
        teleports.delayedTeleport(player, () -> {
            Location spawn = spawnService.find();
            return spawn != null ? spawn : player.getWorld().getSpawnLocation();
        }, "spawn");
        return true;
    }
}
