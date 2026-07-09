package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.BackService;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /back — retour à la position précédente (avant-dernière téléportation ou mort).
 * Le TeleportService ré-enregistre la position de départ : /back est réversible.
 */
public class BackCommand extends EssCommand {

    private final BackService backService;
    private final TeleportService teleports;

    public BackCommand(CommandEnvironment env, BackService backService, TeleportService teleports) {
        super(env, "back", true, false); // cooldown armé par le TeleportService à l'arrivée
        this.backService = backService;
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        final Player player = (Player) sender;
        Location back = backService.find(player.getUniqueId());
        if (back == null) {
            player.sendMessage(Text.error("Aucune position de retour enregistrée."));
            return false;
        }
        // La destination est figée à la position /back actuelle : le record fait
        // par le TeleportService au départ ne doit pas devenir la destination.
        final Location destination = back;
        teleports.delayedTeleport(player, () -> destination, "back");
        return true;
    }
}
