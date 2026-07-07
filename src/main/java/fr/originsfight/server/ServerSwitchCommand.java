package fr.originsfight.server;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import fr.originsfight.core.text.Text;
import fr.originsfight.cooldown.CooldownManager;
import fr.originsfight.cooldown.CooldownType;
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

        player.sendMessage(Text.fmt(RC.SWITCH_SENDING, display));
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(CONNECT_SUBCHANNEL);
        out.writeUTF(target);
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, out.toByteArray());
    }
}
