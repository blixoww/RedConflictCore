package fr.redconflict.staff.commands;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /vanish (/v) — Active/désactive le vanish pour le staff.
 */
public class VanishCommand extends CoreCommand {

    public VanishCommand(JavaPlugin plugin) {
        super(plugin, "vanish", true);
    }
    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("staff.vanish")) {
            p.sendMessage("§cPermission insuffisante."); return;
        }
        StaffManager.get().toggleVanish(p);
    }
}


