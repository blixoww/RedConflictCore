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
import java.util.Arrays;
import java.util.List;

/**
 * /mute <joueur> <durée|perm> <raison...>
 * /unmute <joueur>
 *
 * Durée : 1h, 2j, 30m, perm
 */
public class MuteCommand implements CommandExecutor, TabCompleter {

    private final StaffDatabase db;
    private final StaffListener listener;
    private final boolean isUnmute;

    public MuteCommand(StaffDatabase db, StaffListener listener, boolean isUnmute) {
        this.db = db; this.listener = listener; this.isUnmute = isUnmute;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return true; }
        if (isUnmute) return handleUnmute(sender, args);
        return handleMute(sender, args);
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage : /mute <joueur> <durée|perm> <raison>");
            sender.sendMessage("§7Durées : §f1m, 1h, 1j, perm");
            return true;
        }
        String targetName = args[0];
        String durationStr = args[1];
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        long duration = StaffFormatter.parseDuration(durationStr);
        long expiresAt = duration <= 0 ? -1 : System.currentTimeMillis() + duration;

        // Trouver le joueur (en ligne ou offline)
        Player online = Bukkit.getPlayerExact(targetName);
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return true;
        }
        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : targetName;

        // Vérifier si déjà muté
        if (db.getActiveSanction(uuid, StaffDatabase.SanctionType.MUTE) != null) {
            sender.sendMessage(StaffFormatter.PREFIX + "§c" + name + " est déjà muté. Faites /unmute d'abord.");
            return true;
        }

        db.addSanction(uuid, name, StaffDatabase.SanctionType.MUTE, reason, staffName, expiresAt);

        if (online != null) {
            StaffManager.get().addMuted(online.getUniqueId());
            String expiry = duration <= 0 ? "Permanent" : StaffFormatter.formatDate(expiresAt);
            online.sendMessage(StaffFormatter.muteMessage(reason, expiry));
        }

        String expiryLabel = StaffFormatter.expiryLabel(duration);
        listener.broadcastStaff(StaffFormatter.sanctionBroadcastMute(name, reason, expiryLabel, staffName));
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ §f" + name + " §amuté — " + expiryLabel);
        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§cUsage : /unmute <joueur>"); return true; }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        if (offline == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return true; }
        String uuid = offline.getUniqueId().toString();
        String name = offline.getName() != null ? offline.getName() : args[0];
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean lifted = db.liftSanction(uuid, StaffDatabase.SanctionType.MUTE);
        if (!lifted) { sender.sendMessage(StaffFormatter.PREFIX + "§c" + name + " n'est pas muté."); return true; }

        StaffManager.get().removeMuted(offline.getUniqueId());
        Player online = Bukkit.getPlayer(offline.getUniqueId());
        if (online != null)
            online.sendMessage(StaffFormatter.PREFIX + "§aVotre mute a été levé par §f" + staffName + "§a.");

        listener.broadcastStaff(StaffFormatter.PREFIX + "§a✔ §f" + staffName + " §aa levé le mute de §f" + name);
        sender.sendMessage(StaffFormatter.PREFIX + "§a✔ Mute de §f" + name + " §alevé.");
        return true;
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission(isUnmute ? "staff.unmute" : "staff.mute");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        else if (!isUnmute && args.length == 2)
            list.addAll(Arrays.asList("1m", "1h", "6h", "1j", "7j", "30j", "perm"));
        else if (!isUnmute && args.length == 3)
            list.add("<raison>");
        return list;
    }
}


