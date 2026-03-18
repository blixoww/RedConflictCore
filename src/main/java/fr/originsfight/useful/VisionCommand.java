package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /vision — Toggle la vision nocturne.
 * Permission : redconflict.nv
 */
public class VisionCommand implements CommandExecutor {

    // Cache des joueurs avec vision nocturne activée manuellement
    private static final Set<UUID> VISION_ON = new HashSet<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("redconflict.nv")) {
            p.sendMessage(RC.ERR_NO_PERM); return true;
        }

        if (VISION_ON.contains(p.getUniqueId())) {
            // Désactiver
            VISION_ON.remove(p.getUniqueId());
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.sendMessage(RC.VISION_OFF);
        } else {
            // Activer
            VISION_ON.add(p.getUniqueId());
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    Integer.MAX_VALUE, 0, false, false));
            p.sendMessage(RC.VISION_ON);
        }
        return true;
    }
}

