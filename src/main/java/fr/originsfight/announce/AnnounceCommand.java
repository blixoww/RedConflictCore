package fr.originsfight.announce;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /annonce <message>} — diffuse une annonce stylée sur TOUS les serveurs de la grappe (staff).
 *
 * <p>Le message accepte les codes couleur {@code &}. Le rendu (grand cadre de tirets + message
 * centré) est produit par {@link Announce} et relayé via {@link AnnounceService}.
 */
public class AnnounceCommand extends CoreCommand {

    private final AnnounceService service;

    public AnnounceCommand(JavaPlugin plugin, AnnounceService service) {
        super(plugin, "annonce", false);
        this.service = service;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("redconflict.staff")) {
            sender.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(RC.PRE + "§cUsage : §f/annonce <message>");
            return;
        }

        String message = ChatColor.translateAlternateColorCodes('&', String.join(" ", args));
        String author  = (sender instanceof Player) ? sender.getName() : "Console";
        String fullText = Announce.build(message, author);

        Player carrier = (sender instanceof Player) ? (Player) sender : null;
        service.broadcast(fullText, carrier);
    }
}
