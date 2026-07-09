package fr.redconflict.staff.commands;

import fr.redconflict.staff.StaffFormatter;
import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /clearchat (/cc) — Efface le chat pour tous (envoie 100 lignes vides)
 *                    Le staff voit un message indiquant qui a clear.
 */
public class ClearChatCommand extends CoreCommand {

    public ClearChatCommand(JavaPlugin plugin) {
        super(plugin, "clearchat", false);
    }
    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }

        String name = sender instanceof Player ? ((Player) sender).getName() : "Console";
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < 100; i++) p.sendMessage("");
            if (StaffManager.get().isStaff(p)) {
                p.sendMessage(StaffFormatter.PREFIX + "§7Chat efface par §c" + name + "§7.");
            } else {
                p.sendMessage(StaffFormatter.PREFIX + "§7Le chat a ete efface.");
            }
        }
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.clearchat");
    }
}
