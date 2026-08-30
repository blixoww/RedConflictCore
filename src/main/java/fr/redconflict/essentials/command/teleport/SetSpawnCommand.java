package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /setspawn — définit le spawn du serveur à la position actuelle (admin).
 *
 * <p>Trois choses d'un coup : la destination de {@code /spawn}, le spawn du
 * monde (boussole, mort sans lit) et l'endroit où apparaissent les joueurs qui
 * découvrent la carte — ce dernier via {@code SpawnListener}.
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
        player.sendMessage(Text.info("Les nouveaux joueurs apparaîtront ici."));
        return true;
    }
}
