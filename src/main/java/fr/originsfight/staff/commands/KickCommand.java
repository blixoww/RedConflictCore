package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffDatabase;
import fr.originsfight.staff.StaffFormatter;
import fr.originsfight.staff.StaffListener;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /kick <joueur> [raison...]
 * Kick un joueur du serveur avec raison.
 */
public class KickCommand implements CommandExecutor, TabCompleter {

    private final StaffDatabase db;
    private final StaffListener listener;

    public KickCommand(StaffDatabase db, StaffListener listener) {
        this.db = db; this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return true; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /kick <joueur> [raison]"); return true; }

        Player target = Bukkit.getPlayerExact(args[0]);
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                                        : "Aucune raison précisée";
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (target == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return true; }

        db.addSanction(target.getUniqueId().toString(), target.getName(),
                StaffDatabase.SanctionType.KICK, reason, staffName, -1);

        target.kickPlayer("\n§c§lVous avez été expulsé du serveur\n\n§7Raison §f: §c" + reason +
                "\n§7Par §f: §e" + staffName + "\n\n§7Contestation §f: §bdiscord.gg/originsfight");

        listener.broadcastStaff(StaffFormatter.sanctionBroadcastKick(target.getName(), reason, staffName));
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ §f" + target.getName() + " §aa été kick.");
        return true;
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.kick");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        else if (args.length == 2) list.add("<raison>");
        return list;
    }
}

