package fr.originsfight.staff.commands;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.staff.StaffListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /sc [message]
 *   - Sans argument : toggle le mode "staff chat" (tout ce qu'on tape va dans le staff chat)
 *   - Avec argument  : envoie un message ponctuel dans le staff chat
 */
public class StaffChatCommand extends CoreCommand {

    private final StaffListener listener;

    public StaffChatCommand(JavaPlugin plugin, StaffListener listener) {
        super(plugin, "sc", false); this.listener = listener; }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (!(sender instanceof Player)) {
            // Console : envoi direct
            if (args.length == 0) { sender.sendMessage("§cUsage console : /sc <message>"); return; }
            listener.broadcastStaffChat(null, "Console", String.join(" ", args));
            return;
        }
        Player p = (Player) sender;
        if (args.length == 0) {
            // Toggle
            listener.toggleStaffChatOnly(p);
        } else {
            // Envoi ponctuel (sans toggle)
            listener.broadcastStaffChat(p, p.getName(), String.join(" ", args));
        }
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.staffchat");
    }
}
