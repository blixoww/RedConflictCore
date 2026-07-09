package fr.redconflict.staff.commands;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/** /staffmode (/sm) — Active/désactive le mode staff */
public class StaffModeCommand extends CoreCommand {

    public StaffModeCommand(JavaPlugin plugin) {
        super(plugin, "staffmode", true);
    }
    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("staff.staffmode")) {
            p.sendMessage("§cPermission insuffisante."); return;
        }
        StaffManager.get().toggleStaffMode(p);
    }
}

