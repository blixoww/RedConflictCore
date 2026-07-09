package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** /vision — bascule la vision nocturne (permission redconflict.nv). */
public class VisionCommand extends CoreCommand {

    private final Set<UUID> active = new HashSet<>();

    public VisionCommand(JavaPlugin plugin) {
        super(plugin, "vision", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("redconflict.nv")) {
            player.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        if (active.remove(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            player.sendMessage(RC.VISION_OFF);
        } else {
            active.add(player.getUniqueId());
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    Integer.MAX_VALUE, 0, false, false));
            player.sendMessage(RC.VISION_ON);
        }
    }
}
