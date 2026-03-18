package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /vanish (/v) — Active/désactive le vanish pour le staff.
 */
public class VanishCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("§cJoueur uniquement."); return true; }
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("staff.vanish")) {
            p.sendMessage("§cPermission insuffisante."); return true;
        }
        StaffManager.get().toggleVanish(p);
        return true;
    }
}


