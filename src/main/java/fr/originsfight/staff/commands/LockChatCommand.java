package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffFormatter;
import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.staff.StaffManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /lockchat — Verrouille/déverrouille le chat public.
 * Seul le staff peut parler quand le chat est verrouillé.
 */
public class LockChatCommand extends CoreCommand {

    public LockChatCommand(JavaPlugin plugin) {
        super(plugin, "lockchat", false);
    }
    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }

        String name = sender instanceof Player ? ((Player) sender).getName() : "Console";
        boolean locked = StaffManager.get().toggleChatLock();

        String msg = locked
                ? StaffFormatter.PREFIX + "§c§lLe chat est maintenant verrouillé. §7Seul le staff peut parler."
                : StaffFormatter.PREFIX + "§a§lLe chat est maintenant déverrouillé.";

        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);

        // Info staff uniquement
        String staffInfo = locked
                ? StaffFormatter.PREFIX + "§7(Verrouillé par §c" + name + "§7)"
                : StaffFormatter.PREFIX + "§7(Déverrouillé par §a" + name + "§7)";
        for (Player p : Bukkit.getOnlinePlayers())
            if (StaffManager.get().isStaff(p)) p.sendMessage(staffInfo);
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.lockchat");
    }
}

