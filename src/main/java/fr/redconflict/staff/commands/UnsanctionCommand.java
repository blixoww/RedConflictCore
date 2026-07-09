package fr.redconflict.staff.commands;

import fr.redconflict.staff.StaffDatabase;
import fr.redconflict.staff.StaffFormatter;
import fr.redconflict.staff.StaffListener;
import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffManager;
import org.bukkit.plugin.java.JavaPlugin;
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
public class UnsanctionCommand extends CoreCommand {

    private final StaffDatabase db;
    private final StaffListener listener;

    public UnsanctionCommand(JavaPlugin plugin, StaffDatabase db, StaffListener listener) {
        super(plugin, "unsanction", false);
        this.db = db;
        this.listener = listener;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /unsanction <joueur>"); return; }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return;
        }

        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : args[0];
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        int count = db.resetAllSanctions(uuid);
        if (count == 0) {
            sender.sendMessage(StaffFormatter.PREFIX + "§7" + name + " n'a aucune sanction active.");
            return;
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

