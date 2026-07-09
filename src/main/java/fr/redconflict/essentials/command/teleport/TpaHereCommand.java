package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.model.TeleportRequest;
import fr.redconflict.essentials.service.TeleportRequestService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tpahere <joueur> — demande à un joueur de venir se téléporter vers soi.
 */
public class TpaHereCommand extends EssCommand {

    private final TeleportRequestService requests;

    public TpaHereCommand(CommandEnvironment env, TeleportRequestService requests) {
        super(env, "tpahere", true, true);
        this.requests = requests;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Text.error("Usage : /tpahere <joueur>"));
            return false;
        }
        Player target = findOnline(sender, args[0]);
        if (target == null) return false;
        return requests.request((Player) sender, target, TeleportRequest.Type.TO_REQUESTER);
    }
}
