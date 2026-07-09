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
 * /kick <joueur> [raison...]
 * Kick un joueur du serveur avec raison.
 */
public class KickCommand extends CoreCommand {

    private final StaffDatabase db;
    private final StaffListener listener;

    public KickCommand(JavaPlugin plugin, StaffDatabase db, StaffListener listener) {
        super(plugin, "kick", false);
        this.db = db; this.listener = listener;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /kick <joueur> [raison]"); return; }

        Player target = Bukkit.getPlayerExact(args[0]);
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                                        : "Aucune raison précisée";
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (target == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return; }

        db.addSanction(target.getUniqueId().toString(), target.getName(),
                StaffDatabase.SanctionType.KICK, reason, staffName, -1);

        target.kickPlayer("\n§c§lVous avez été expulsé du serveur\n\n§7Raison §f: §c" + reason +
                "\n§7Par §f: §e" + staffName + "\n\n§7Contestation §f: §bdiscord.gg/originsfight");

        listener.broadcastStaff(StaffFormatter.sanctionBroadcastKick(target.getName(), reason, staffName));
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ §f" + target.getName() + " §aa été kick.");
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

