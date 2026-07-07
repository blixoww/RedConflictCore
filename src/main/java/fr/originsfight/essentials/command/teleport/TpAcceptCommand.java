package fr.originsfight.essentials.command.teleport;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.TeleportRequestService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tpaccept (alias /tpyes) — accepte la demande de téléportation en attente.
 */
public class TpAcceptCommand extends EssCommand {

    private final TeleportRequestService requests;

    public TpAcceptCommand(CommandEnvironment env, TeleportRequestService requests) {
        super(env, "tpaccept", true, false);
        this.requests = requests;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        requests.accept((Player) sender);
        return true;
    }
}
