package fr.originsfight.essentials.command.teleport;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.TeleportService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tp <joueur> [joueur2] — téléportation directe (admin, sans délai).
 * Avec un seul argument, téléporte l'exécutant vers la cible ;
 * avec deux, téléporte le premier joueur vers le second.
 */
public class TpCommand extends EssCommand {

    private final TeleportService teleports;

    public TpCommand(CommandEnvironment env, TeleportService teleports) {
        super(env, "tp", false, false);
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            Player target = findOnline(sender, args[0]);
            if (target == null) return false;
            teleports.teleportNow((Player) sender, target.getLocation());
            sender.sendMessage(Text.success("Téléporté vers §f" + target.getName() + "§a."));
            return true;
        }
        if (args.length >= 2) {
            Player moved = findOnline(sender, args[0]);
            if (moved == null) return false;
            Player destination = findOnline(sender, args[1]);
            if (destination == null) return false;
            teleports.teleportNow(moved, destination.getLocation());
            sender.sendMessage(Text.success("§f" + moved.getName() + " §atéléporté vers §f"
                    + destination.getName() + "§a."));
            moved.sendMessage(Text.info("Vous avez été téléporté vers §f" + destination.getName() + "§7."));
            return true;
        }
        sender.sendMessage(Text.error("Usage : /tp <joueur> [joueur2]"));
        return false;
    }
}
