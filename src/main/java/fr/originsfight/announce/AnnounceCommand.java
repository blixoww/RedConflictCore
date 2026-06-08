package fr.originsfight.announce;

import fr.originsfight.RC;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /annonce <message>} — diffuse une annonce stylée sur TOUS les serveurs de la grappe (staff).
 *
 * <p>Le message accepte les codes couleur {@code &}. Le rendu (grand cadre de tirets + message
 * centré) est produit par {@link Announce} et relayé via {@link AnnounceService}.
 */
public class AnnounceCommand implements CommandExecutor {

    private final AnnounceService service;

    public AnnounceCommand(AnnounceService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("redconflict.staff")) {
            sender.sendMessage(RC.ERR_NO_PERM);
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(RC.PRE + "§cUsage : §f/annonce <message>");
            return true;
        }

        String message = ChatColor.translateAlternateColorCodes('&', String.join(" ", args));
        String author  = (sender instanceof Player) ? sender.getName() : "Console";
        String fullText = Announce.build(message, author);

        Player carrier = (sender instanceof Player) ? (Player) sender : null;
        service.broadcast(fullText, carrier);
        return true;
    }
}
