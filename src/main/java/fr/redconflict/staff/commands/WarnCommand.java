package fr.redconflict.staff.commands;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffDatabase;
import fr.redconflict.staff.StaffFormatter;
import fr.redconflict.staff.StaffListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /warn <joueur> <raison...>
 * Ajoute un avertissement, affiche le total au joueur et broadcast au staff.
 */
public class WarnCommand extends CoreCommand {

    private final StaffDatabase db;
    private final StaffListener listener;

    public WarnCommand(JavaPlugin plugin, StaffDatabase db, StaffListener listener) {
        super(plugin, "warn", false);
        this.db = db; this.listener = listener;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (args.length < 2) { sender.sendMessage("§cUsage : /warn <joueur> <raison>"); return; }

        Player target = Bukkit.getPlayerExact(args[0]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (target == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return; }

        db.addSanction(target.getUniqueId().toString(), target.getName(),
                StaffDatabase.SanctionType.WARN, reason, staffName, -1);

        // Compter les warns actifs
        long warnCount = db.getHistory(target.getUniqueId().toString()).stream()
                .filter(s -> s.type == StaffDatabase.SanctionType.WARN && s.active).count();

        target.sendMessage(StaffFormatter.warnMessage((int) warnCount, reason, staffName));
        listener.broadcastStaff(StaffFormatter.sanctionBroadcastWarn(target.getName(), reason, staffName));
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ Warn ajouté à §f" + target.getName() +
                " §7(total : §c" + warnCount + " §7warn(s))");
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.warn");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        else if (args.length == 2)
            list.add("<raison>");
        return list;
    }
}

