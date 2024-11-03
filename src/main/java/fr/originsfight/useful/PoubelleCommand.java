package fr.originsfight.useful;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PoubelleCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        if (label.equalsIgnoreCase("poubelle")) {
            Bukkit.createInventory(null, 54, "§bPoubelle");
            return true;
        }
        return false;
    }

}
