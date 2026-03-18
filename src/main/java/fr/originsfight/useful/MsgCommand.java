package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /msg <joueur> <message> — Messagerie privée stylisée.
 * /r <message>            — Répondre au dernier message reçu.
 *
 * Alias : /tell, /w, /whisper, /pm, /emsg, /etell, /r, /reply
 *
 * Staff ayant staff.msgspy → voit tous les MP en mode spy (configurable).
 */
public class MsgCommand implements CommandExecutor, TabCompleter {

    // Dernier interlocuteur pour /r
    private static final Map<UUID, UUID> LAST_MSG = new HashMap<>();

    // Staff avec msgspy activé
    private static final Set<UUID> SPY_MODE = new HashSet<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        // /r [message] — répondre
        if (cmd.equals("r") || cmd.equals("reply")) {
            return handleReply(sender, args);
        }

        // /msgspy — toggle spy pour le staff
        if (cmd.equals("msgspy")) {
            return handleSpy(sender);
        }

        // /msg <joueur> <message>
        if (args.length < 2) { sender.sendMessage(RC.MSG_USAGE); return true; }
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }

        Player from = (Player) sender;
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null || !to.isOnline()) { from.sendMessage(RC.MSG_NOT_FOUND); return true; }
        if (to.equals(from)) { from.sendMessage(RC.MSG_SELF); return true; }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        sendPrivateMessage(from, to, message);
        return true;
    }

    private boolean handleReply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player from = (Player) sender;
        UUID lastUUID = LAST_MSG.get(from.getUniqueId());
        if (lastUUID == null) { from.sendMessage(RC.MSG_NO_REPLY); return true; }
        Player to = Bukkit.getPlayer(lastUUID);
        if (to == null || !to.isOnline()) { from.sendMessage(RC.MSG_NOT_FOUND); return true; }
        if (args.length == 0) { from.sendMessage(RC.MSG_USAGE); return true; }
        sendPrivateMessage(from, to, String.join(" ", args));
        return true;
    }

    private boolean handleSpy(CommandSender sender) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player p = (Player) sender;
        if (!p.isOp() && !p.hasPermission("staff.msgspy")) { p.sendMessage(RC.ERR_NO_PERM); return true; }
        if (SPY_MODE.contains(p.getUniqueId())) {
            SPY_MODE.remove(p.getUniqueId());
            p.sendMessage(RC.PRE + "§cSpy MP §cdésactivé.");
        } else {
            SPY_MODE.add(p.getUniqueId());
            p.sendMessage(RC.PRE + "§aSpy MP §aactivé §7— vous voyez tous les messages privés.");
        }
        return true;
    }

    private void sendPrivateMessage(Player from, Player to, String message) {
        // Côté envoyeur : "moi -> Destinataire : message"
        from.sendMessage("§8[§7MP§8] §fmoi §8-> §a" + to.getName() + " §7» §f" + message);
        // Côté receveur : "Expéditeur -> moi : message"
        to.sendMessage("§8[§7MP§8] §a" + from.getName() + " §8-> §fmoi §7» §f" + message);

        // Mettre à jour le dernier interlocuteur (pour /r)
        LAST_MSG.put(from.getUniqueId(), to.getUniqueId());
        LAST_MSG.put(to.getUniqueId(), from.getUniqueId());

        // Spy staff
        String spyMsg = "§8[§dSpy§8] §e" + from.getName() + " §8-> §e" + to.getName() + " §7» §7" + message;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.equals(from) || staff.equals(to)) continue;
            if (SPY_MODE.contains(staff.getUniqueId()) &&
                (staff.isOp() || staff.hasPermission("staff.msgspy"))) {
                staff.sendMessage(spyMsg);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.equals(sender)) list.add(p.getName());
        }
        return list;
    }
}

