package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffDatabase;
import fr.originsfight.staff.StaffFormatter;
import fr.originsfight.staff.StaffListener;
import fr.originsfight.staff.StaffManager;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /freeze <joueur> — Freeze ou dégèle un joueur */
public class FreezeCommand implements CommandExecutor, TabCompleter {

    private final StaffListener listener;
    private final StaffDatabase db;

    public FreezeCommand(StaffListener listener, StaffDatabase db) {
        this.listener = listener;
        this.db = db;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return true; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /freeze <joueur>"); return true; }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return true; }

        boolean frozen = StaffManager.get().toggleFreeze(target);
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (frozen) {
            target.sendMessage(StaffFormatter.freezeMessage(staffName));
            listener.broadcastStaff(StaffFormatter.PREFIX + "§3❄ " + staffName + " §fa freezé §3" + target.getName());
        } else {
            target.sendMessage(StaffFormatter.PREFIX + "§aVous avez été défreezé par §f" + staffName + "§a.");
            listener.broadcastStaff(StaffFormatter.PREFIX + "§a✦ " + staffName + " §fa défreezé §a" + target.getName());
        }
        return true;
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true; // Console = toujours ok
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.freeze");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        return list;
    }
}

