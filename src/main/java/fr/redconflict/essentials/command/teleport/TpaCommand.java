package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.model.TeleportRequest;
import fr.redconflict.essentials.service.TeleportRequestService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tpa <joueur> — demande à se téléporter vers un joueur (expire après 60 s).
 */
public class TpaCommand extends EssCommand {

    private final TeleportRequestService requests;

    public TpaCommand(CommandEnvironment env, TeleportRequestService requests) {
        super(env, "tpa", true, true);
        this.requests = requests;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Text.error("Usage : /tpa <joueur>"));
            return false;
        }
        Player target = findOnline(sender, args[0]);
        if (target == null) return false;
        return requests.request((Player) sender, target, TeleportRequest.Type.TO_TARGET);
    }
}
