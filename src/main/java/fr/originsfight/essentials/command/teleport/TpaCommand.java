package fr.originsfight.essentials.command.teleport;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.model.TeleportRequest;
import fr.originsfight.essentials.service.TeleportRequestService;
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
