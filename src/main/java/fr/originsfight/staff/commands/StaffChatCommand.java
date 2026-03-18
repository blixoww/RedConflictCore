package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffListener;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /sc [message]
 *   - Sans argument : toggle le mode "staff chat" (tout ce qu'on tape va dans le staff chat)
 *   - Avec argument  : envoie un message ponctuel dans le staff chat
 */
public class StaffChatCommand implements CommandExecutor {

    private final StaffListener listener;

    public StaffChatCommand(StaffListener listener) { this.listener = listener; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return true; }
        if (!(sender instanceof Player)) {
            // Console : envoi direct
            if (args.length == 0) { sender.sendMessage("§cUsage console : /sc <message>"); return true; }
            listener.broadcastStaffChat(null, "Console", String.join(" ", args));
            return true;
        }
        Player p = (Player) sender;
        if (args.length == 0) {
            // Toggle
            listener.toggleStaffChatOnly(p);
        } else {
            // Envoi ponctuel (sans toggle)
            listener.broadcastStaffChat(p, p.getName(), String.join(" ", args));
        }
        return true;
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.staffchat");
    }
}
