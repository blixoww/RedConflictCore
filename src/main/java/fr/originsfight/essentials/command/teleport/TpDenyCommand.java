package fr.originsfight.essentials.command.teleport;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.TeleportRequestService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tpno — refuse la demande de téléportation en attente.
 */
public class TpDenyCommand extends EssCommand {

    private final TeleportRequestService requests;

    public TpDenyCommand(CommandEnvironment env, TeleportRequestService requests) {
        super(env, "tpno", true, false);
        this.requests = requests;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        requests.deny((Player) sender);
        return true;
    }
}
