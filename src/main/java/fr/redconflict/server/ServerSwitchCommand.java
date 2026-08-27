package fr.redconflict.server;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.db.HandoffService;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.cooldown.CooldownManager;
import fr.redconflict.cooldown.CooldownType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /hub, /minage, /faction — transfert du joueur vers un autre serveur du
 * cluster Velocity (une instance par destination, câblées par le module).
 *
 * <p>Envoie un message plugin BungeeCord/Velocity (sous-canal « Connect ») au
 * proxy. Le transfert est bloqué tant que le joueur est en Combat Tag
 * (anti-fuite PvP).
 */
public class ServerSwitchCommand extends CoreCommand {

    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String CONNECT_SUBCHANNEL = "Connect";

    private final String target;
    private final String display;

    public ServerSwitchCommand(JavaPlugin plugin, String target, String display) {
        super(plugin, target, true);
        this.target = target;
        this.display = display;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        long left = CooldownManager.instance().timeLeft(player, CooldownType.COMBAT);
        if (left > 0) {
            player.sendMessage(Text.fmt(RC.CT_IN_COMBAT, Text.duration(left)));
            return;
        }

        // Sauvegarder et relâcher le verrou AVANT de demander le transfert.
        // C'est le seul instant où ce serveur peut le faire utilement : une fois
        // le message Connect envoyé, le proxy ouvre la session sur le serveur
        // d'arrivée et n'y coupe la nôtre qu'après — l'arrivée chargerait donc
        // un inventaire d'avant le transfert. Voir HandoffService.
        HandoffService handoff = RedConflictCore.getInstance().getHandoff();
        if (handoff != null && !handoff.handOff(player)) {
            player.sendMessage(RC.ERR_INTERNAL);
            return; // ne pas envoyer un joueur dont on n'a pas su sauvegarder l'état
        }

        player.sendMessage(Text.fmt(RC.SWITCH_SENDING, display));
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(CONNECT_SUBCHANNEL);
        out.writeUTF(target);
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, out.toByteArray());
    }
}
