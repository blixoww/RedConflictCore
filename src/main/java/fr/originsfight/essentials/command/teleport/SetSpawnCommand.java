package fr.originsfight.essentials.command.teleport;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /setspawn — définit le spawn du serveur à la position actuelle (admin).
 */
public class SetSpawnCommand extends EssCommand {

    private final SpawnService spawnService;

    public SetSpawnCommand(CommandEnvironment env, SpawnService spawnService) {
        super(env, "setspawn", true, false);
        this.spawnService = spawnService;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        Location location = player.getLocation();
        spawnService.set(location);
        player.sendMessage(Text.success("Spawn défini en §f" + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ()
                + " §7(" + location.getWorld().getName() + ")§a."));
        return true;
    }
}
