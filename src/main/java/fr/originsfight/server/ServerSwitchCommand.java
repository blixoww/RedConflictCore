package fr.originsfight.server;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /hub et /minage — téléporte le joueur vers un autre serveur du cluster Velocity.
 *
 * Envoie un message plugin BungeeCord/Velocity (sous-canal "Connect") sur le
 * canal legacy "BungeeCord" pour demander au proxy de transférer le joueur.
 * Le nom du serveur cible est déduit du label de la commande :
 *   /hub    → serveur "hub"
 *   /minage → serveur "minage"
 *
 * Le transfert est bloqué tant que le joueur est en Combat Tag (anti-fuite PvP).
 */
public class ServerSwitchCommand implements CommandExecutor {

    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String CONNECT_SUBCHANNEL = "Connect";

    private final OriginsFightCore plugin;

    public ServerSwitchCommand(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player player = (Player) sender;

        // Résout le serveur cible + son libellé d'affichage depuis le label utilisé.
        String target;
        String display;
        switch (command.getName().toLowerCase()) {
            case "hub":
                target = "hub";
                display = "§9§lHUB";
                break;
            case "minage":
                target = "minage";
                display = "§e§lMINAGE";
                break;
            case "faction":
                target = "faction";
                display = "§c§lFACTION";
                break;
            default:
                return true;
        }

        // Anti-fuite : interdit le transfert pendant un Combat Tag actif.
        long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.COMBAT);
        if (timeLeft > 0) {
            player.sendMessage(RC.fmt(RC.CT_IN_COMBAT, CooldownManager.getFormattedTimeLeft(timeLeft)));
            return true;
        }

        player.sendMessage(RC.PRE + "§7Téléportation vers le serveur " + display + " §7...");

        // Demande au proxy de transférer le joueur vers le serveur cible.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(CONNECT_SUBCHANNEL);
        out.writeUTF(target);
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, out.toByteArray());

        return true;
    }
}
