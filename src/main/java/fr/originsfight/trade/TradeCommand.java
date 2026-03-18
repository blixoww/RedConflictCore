package fr.originsfight.trade;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TradeCommand implements CommandExecutor, TabCompleter {

    private final TradeManager manager = TradeManager.getInstance();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;
        if (args.length == 0) { player.sendMessage(RC.TRADE_USAGE); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("accept")) handleAccept(player);
        else if (sub.equals("deny") || sub.equals("decline") || sub.equals("refuser")) handleDeny(player);
        else handleInvite(player, args[0]);
        return true;
    }

    private void handleInvite(Player player, String targetName) {
        if (manager.isInTrade(player)) { player.sendMessage(RC.TRADE_IN_PROG); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { player.sendMessage(RC.TRADE_NOT_FOUND); return; }
        if (target.equals(player)) { player.sendMessage(RC.TRADE_SELF); return; }
        if (manager.isInTrade(target)) {
            player.sendMessage(RC.PRE + "§c" + target.getName() + " est déjà en train de trader."); return;
        }
        UUID existingInviter = manager.getPendingInviter(player);
        if (existingInviter != null && existingInviter.equals(target.getUniqueId())) {
            doAccept(player, target); return;
        }
        if (!manager.invite(player, target)) { player.sendMessage(RC.TRADE_ALREADY); return; }
        player.sendMessage(RC.fmt(RC.TRADE_SENT, target.getName()));
        target.sendMessage(RC.fmt(RC.TRADE_RECEIVED, player.getName()));
    }

    private void handleAccept(Player player) {
        UUID inviterUUID = manager.getPendingInviter(player);
        if (inviterUUID == null) { player.sendMessage(RC.TRADE_NO_REQ); return; }
        Player inviter = Bukkit.getPlayer(inviterUUID);
        if (inviter == null || !inviter.isOnline()) {
            manager.cleanupPlayer(player);
            player.sendMessage(RC.TRADE_NOT_FOUND); return;
        }
        doAccept(player, inviter);
    }

    private void doAccept(Player accepter, Player inviter) {
        manager.acceptInvite(inviter, accepter);
        inviter.sendMessage(RC.fmt(RC.TRADE_ACCEPTED, accepter.getName()));
        accepter.sendMessage(RC.fmt(RC.TRADE_ACCEPTED, inviter.getName()));
        String hint = RC.PRE + "§7Placez vos items et cliquez sur §aConfirmer §7quand vous êtes prêt.";
        inviter.sendMessage(hint);
        accepter.sendMessage(hint);
    }

    private void handleDeny(Player player) {
        UUID inviterUUID = manager.getPendingInviter(player);
        if (inviterUUID == null) { player.sendMessage(RC.TRADE_NO_REQ); return; }
        Player inviter = Bukkit.getPlayer(inviterUUID);
        manager.declineInvite(inviter != null ? inviter : player);
        player.sendMessage(RC.TRADE_CANCELLED);
        if (inviter != null && inviter.isOnline())
            inviter.sendMessage(RC.fmt(RC.TRADE_DENIED, player.getName()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("accept"); list.add("deny");
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.equals(sender)) list.add(p.getName());
        }
        return list;
    }
}
