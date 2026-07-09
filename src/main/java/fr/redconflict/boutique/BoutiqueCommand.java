package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
public class BoutiqueCommand extends CoreCommand {

    private static final String PERM_ADMIN = "redconflict.boutique.admin";

    private final RedConflictCore core;

    public BoutiqueCommand(RedConflictCore plugin) {
        super(plugin, "pbshop", false);
        this.core = plugin;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(ChatColor.RED + "Permission refusée."); return; }
            core.reloadConfig();
            core.getOffresManager().reload();
            core.getOffresManager().rerollNow();
            sender.sendMessage(ChatColor.GREEN + "[PBShop] Configuration et offres rechargées.");
            return;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("offre")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(ChatColor.RED + "Permission refusée."); return; }
            String id = args[1];
            String action = args[2].toLowerCase();
            switch (action) {
                case "enable":
                    if (core.getOffresManager().setEnabled(id, true))
                        sender.sendMessage(ChatColor.GREEN + "Offre " + id + " activée.");
                    else sender.sendMessage(ChatColor.RED + "Offre inconnue : " + id);
                    break;
                case "disable":
                    if (core.getOffresManager().setEnabled(id, false))
                        sender.sendMessage(ChatColor.YELLOW + "Offre " + id + " désactivée.");
                    else sender.sendMessage(ChatColor.RED + "Offre inconnue : " + id);
                    break;
                case "reroll":
                    core.getOffresManager().rerollNow();
                    sender.sendMessage(ChatColor.AQUA + "Offre actuelle régénérée.");
                    break;
                default:
                    sender.sendMessage(ChatColor.RED + "Action : enable | disable | reroll");
            }
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Réservé aux joueurs.");
            return;
        }
        // Envoie la snapshot au client modifié — il ouvre GuiBoutique à la réception.
        BoutiquePacketSender.sendData((Player) sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) return Collections.emptyList();
        if (args.length == 1) return filter(Arrays.asList("offre", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("offre"))
            return filter(new ArrayList<>(core.getOffresManager().listIds()), args[1]);
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
