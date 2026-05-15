package fr.originsfight.boutique;

import fr.originsfight.OriginsFightCore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /pbshop                       → ouvre le menu
 * /pbshop offre <id> enable|disable|reroll  → admin
 * /pbshop reload                → recharge config + offres (admin)
 * Permission admin : redconflict.boutique.admin
 */
public class BoutiqueCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "redconflict.boutique.admin";

    private final OriginsFightCore plugin;

    public BoutiqueCommand(OriginsFightCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(ChatColor.RED + "Permission refusée."); return true; }
            plugin.reloadConfig();
            plugin.getOffresManager().reload();
            plugin.getOffresManager().rerollNow();
            sender.sendMessage(ChatColor.GREEN + "[PBShop] Configuration et offres rechargées.");
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("offre")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(ChatColor.RED + "Permission refusée."); return true; }
            String id = args[1];
            String action = args[2].toLowerCase();
            switch (action) {
                case "enable":
                    if (plugin.getOffresManager().setEnabled(id, true))
                        sender.sendMessage(ChatColor.GREEN + "Offre " + id + " activée.");
                    else sender.sendMessage(ChatColor.RED + "Offre inconnue : " + id);
                    break;
                case "disable":
                    if (plugin.getOffresManager().setEnabled(id, false))
                        sender.sendMessage(ChatColor.YELLOW + "Offre " + id + " désactivée.");
                    else sender.sendMessage(ChatColor.RED + "Offre inconnue : " + id);
                    break;
                case "reroll":
                    plugin.getOffresManager().rerollNow();
                    sender.sendMessage(ChatColor.AQUA + "Offre actuelle régénérée.");
                    break;
                default:
                    sender.sendMessage(ChatColor.RED + "Action : enable | disable | reroll");
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Réservé aux joueurs.");
            return true;
        }
        Player p = (Player) sender;
        // Envoie la snapshot au client modifié — il ouvre GuiBoutique à la réception.
        BoutiquePacketSender.sendData(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) return Collections.emptyList();
        if (args.length == 1) return filter(Arrays.asList("offre", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("offre"))
            return filter(new ArrayList<>(plugin.getOffresManager().listIds()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("offre"))
            return filter(Arrays.asList("enable", "disable", "reroll"), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String prefix) {
        List<String> out = new ArrayList<>();
        String pl = prefix.toLowerCase();
        for (String s : src) if (s.toLowerCase().startsWith(pl)) out.add(s);
        return out;
    }
}
