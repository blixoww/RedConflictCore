package fr.originsfight.staff.commands;

import fr.originsfight.staff.StaffDatabase;
import fr.originsfight.staff.StaffFormatter;
import fr.originsfight.staff.StaffListener;
import fr.originsfight.staff.StaffManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /unsanction <joueur>
 * Remet à zéro l'ensemble des sanctions actives d'un joueur (warn, mute, ban).
 * Lève aussi le mute en cache mémoire.
 */
public class UnsanctionCommand implements CommandExecutor, TabCompleter {

    private final StaffDatabase db;
    private final StaffListener listener;

    public UnsanctionCommand(StaffDatabase db, StaffListener listener) {
        this.db = db;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return true; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /unsanction <joueur>"); return true; }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return true;
        }

        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : args[0];
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        int count = db.resetAllSanctions(uuid);
        if (count == 0) {
            sender.sendMessage(StaffFormatter.PREFIX + "§7" + name + " n'a aucune sanction active.");
            return true;
        }

        // Lever le mute du cache mémoire si en ligne
        StaffManager.get().removeMuted(offline.getUniqueId());

        Player online = Bukkit.getPlayer(offline.getUniqueId());
        if (online != null) {
            online.sendMessage(StaffFormatter.PREFIX + "§aToutes vos sanctions ont ete levees par §f" + staffName + "§a.");
        }

        listener.broadcastStaff(StaffFormatter.PREFIX + "§a[Reset] §f" + staffName
                + " §aa efface §e" + count + " §asanction(s) de §f" + name);
        sender.sendMessage(StaffFormatter.PREFIX + "§a" + count + " sanction(s) de §f" + name + " §alevee(s).");
        return true;
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        return ((Player) s).isOp() || ((Player) s).hasPermission("staff.unsanction");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        return list;
    }
}

