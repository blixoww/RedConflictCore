package fr.originsfight.trade;

import fr.originsfight.core.text.Text;
import fr.originsfight.core.text.RC;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import fr.originsfight.core.command.CoreCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /trade <joueur> | accept | deny — invitations d'échange avec boutons cliquables. */
public class TradeCommand extends CoreCommand {

    private final TradeManager manager;

    public TradeCommand(JavaPlugin plugin, TradeManager manager) {
        super(plugin, "trade", true);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) { player.sendMessage(RC.TRADE_USAGE); return; }

        String sub = args[0].toLowerCase();
        if (sub.equals("accept")) handleAccept(player);
        else if (sub.equals("deny") || sub.equals("decline") || sub.equals("refuser")) handleDeny(player);
        else handleInvite(player, args[0]);
    }

    private void handleInvite(Player player, String targetName) {
        if (manager.isInTrade(player)) { player.sendMessage(RC.TRADE_IN_PROG); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { player.sendMessage(RC.ERR_PLAYER_NOT_FOUND); return; }
        if (target.equals(player)) { player.sendMessage(RC.TRADE_SELF); return; }
        if (manager.isInTrade(target)) {
            player.sendMessage(RC.PRE + "§c" + target.getName() + " est déjà en train de trader."); return;
        }
        UUID existingInviter = manager.getPendingInviter(player);
        if (existingInviter != null && existingInviter.equals(target.getUniqueId())) {
            doAccept(player, target); return;
        }
        if (!manager.invite(player, target)) { player.sendMessage(RC.TRADE_ALREADY); return; }
        player.sendMessage(Text.fmt(RC.TRADE_SENT, target.getName()));
        sendInteractiveInvite(target, player.getName());
    }

    /**
     * Envoie au destinataire un message d'invitation avec deux boutons cliquables
     * [✔ ACCEPTER] / [✖ REFUSER] qui lancent /trade accept ou /trade deny.
     */
    private void sendInteractiveInvite(Player target, String inviterName) {
        target.sendMessage(RC.PRE + "§e" + inviterName + " §7vous propose un échange.");

        TextComponent indent = new TextComponent("                  ");

        TextComponent accept = new TextComponent("§a§l[✔ ACCEPTER]");
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade accept"));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§aAccepter l'échange").create()));

        TextComponent gap = new TextComponent("   ");

        TextComponent deny = new TextComponent("§c§l[✖ REFUSER]");
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade deny"));
        deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§cRefuser l'échange").create()));

        target.spigot().sendMessage(indent, accept, gap, deny);
    }

    private void handleAccept(Player player) {
        UUID inviterUUID = manager.getPendingInviter(player);
        if (inviterUUID == null) { player.sendMessage(RC.TRADE_NO_REQ); return; }
        Player inviter = Bukkit.getPlayer(inviterUUID);
        if (inviter == null || !inviter.isOnline()) {
            manager.cleanupPlayer(player);
            player.sendMessage(RC.ERR_PLAYER_NOT_FOUND); return;
        }
        doAccept(player, inviter);
    }

    private void doAccept(Player accepter, Player inviter) {
        manager.acceptInvite(inviter, accepter);
        inviter.sendMessage(Text.fmt(RC.TRADE_ACCEPTED, accepter.getName()));
        accepter.sendMessage(Text.fmt(RC.TRADE_ACCEPTED, inviter.getName()));
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
            inviter.sendMessage(Text.fmt(RC.TRADE_DENIED, player.getName()));
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
