package fr.originsfight.staff.commands;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.staff.StaffDatabase;
import fr.originsfight.staff.StaffFormatter;
import fr.originsfight.staff.StaffListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /ban <joueur> <durée|perm> <raison...>
 * /unban <joueur>
 *
 * Si le joueur est en ligne → kick immédiat avec écran de ban.
 * Vérification IP au login pour éviter le contournement.
 */
public class BanCommand extends CoreCommand {

    private final StaffDatabase db;
    private final StaffListener listener;
    private final boolean isUnban;

    public BanCommand(JavaPlugin plugin, StaffDatabase db, StaffListener listener, boolean isUnban) {
        super(plugin, "ban", false);
        this.db = db; this.listener = listener; this.isUnban = isUnban;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (isUnban) {
            handleUnban(sender, args);
            return;
        }
        handleBan(sender, args);
    }

    private void handleBan(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage : /ban <joueur> <durée|perm> <raison>");
            sender.sendMessage("§7Durées : §f1m, 1h, 1j, 7j, 30j, perm");
            return;
        }
        String targetName = args[0];
        String durationStr = args[1];
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        long duration = StaffFormatter.parseDuration(durationStr);
        long expiresAt = duration <= 0 ? -1 : System.currentTimeMillis() + duration;

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (offline == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return; }
        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : targetName;

        // Vérifier si déjà banni
        if (db.getActiveSanction(uuid, StaffDatabase.SanctionType.BAN) != null) {
            sender.sendMessage(StaffFormatter.PREFIX + "§c" + name + " est déjà banni. /unban d'abord.");
            return;
        }

        db.addSanction(uuid, name, StaffDatabase.SanctionType.BAN, reason, staffName, expiresAt);

        // Kick si en ligne
        Player online = Bukkit.getPlayer(offline.getUniqueId());
        String expiry = duration <= 0 ? "Permanent" : StaffFormatter.formatDate(expiresAt);
        if (online != null) {
            online.kickPlayer(StaffFormatter.banScreen(reason, expiry, staffName));
        }

        String expiryLabel = StaffFormatter.expiryLabel(duration);
        // Broadcast global + staff
        String broadcastMsg = StaffFormatter.sanctionBroadcastBan(name, reason, expiryLabel, staffName);
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.isOp() || p.hasPermission("staff.staff")) p.sendMessage(broadcastMsg);

        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ §f" + name + " §abanni — " + expiryLabel);
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§cUsage : /unban <joueur>"); return; }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        if (offline == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return; }
        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : args[0];
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean lifted = db.liftSanction(uuid, StaffDatabase.SanctionType.BAN);
        if (!lifted) { sender.sendMessage(StaffFormatter.PREFIX + "§c" + name + " n'est pas banni."); return; }

        listener.broadcastStaff(StaffFormatter.PREFIX + "§a✔ §f" + staffName + " §aa débanni §f" + name);
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ §f" + name + " §adébanni avec succès.");
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission(isUnban ? "staff.unban" : "staff.ban");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        else if (!isUnban && args.length == 2)
            list.addAll(Arrays.asList("1h", "6h", "1j", "7j", "30j", "perm"));
        else if (!isUnban && args.length == 3)
            list.add("<raison>");
        return list;
    }
}


