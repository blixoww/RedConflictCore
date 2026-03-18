package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/** /staffmode (/sm) — Active/désactive le mode staff */
public class StaffModeCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("§cJoueur uniquement."); return true; }
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("staff.staffmode")) {
            p.sendMessage("§cPermission insuffisante."); return true;
        }
        StaffManager.get().toggleStaffMode(p);
        return true;
    }
}

