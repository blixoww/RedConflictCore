package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import fr.redconflict.packets.PacketReader;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;

/**
 * Paquets succès, client → serveur, sur {@code CUSTOM:ACH_C2S}.
 *
 * <pre>
 * 0xF1 ACH_REQUEST  (sans charge) — renvoie catalogue + avancement
 * 0xF2 ACH_CLAIM    String id     — réclame une récompense ; « * » = tout
 * </pre>
 *
 * <p><b>Rien n'est cru sur parole.</b> Le client n'envoie jamais d'avancement,
 * seulement une intention. La décision — « est-ce débloqué ? déjà encaissé ? y
 * a-t-il la place ? » — appartient entièrement à {@link SuccesManager}, qui
 * relit son propre état.
 */
public class SuccesServerHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:ACH_C2S";

    private static final int PKT_REQUEST = 0xF1;
    private static final int PKT_CLAIM = 0xF2;

    private final RedConflictCore plugin;
    private final SuccesManager manager;
    private final SuccesPacketSender sender;

    public SuccesServerHandler(RedConflictCore plugin, SuccesManager manager, SuccesPacketSender sender) {
        this.plugin = plugin;
        this.manager = manager;
        this.sender = sender;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_C2S.equals(channel)) return;
        try {
            PacketReader reader = new PacketReader(message);
            int packet = reader.readPacketId();

            switch (packet) {
                case PKT_REQUEST:
                    sender.sendInit(player);
                    sender.sendData(player);
                    break;

                case PKT_CLAIM: {
                    String id = reader.readString(64);
                    if ("*".equals(id)) {
                        int count = manager.claimAll(player);
                        if (count == 0) {
                            player.sendMessage("§7Aucune récompense à récupérer.");
                        }
                    } else {
                        report(player, id, manager.claim(player, id));
                    }
                    sender.sendData(player);
                    break;
                }

                default:
                    plugin.getLogger().warning("[Succes] Packet C2S inconnu : 0x"
                            + Integer.toHexString(packet) + " de " + player.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Succes] Erreur lecture packet : " + e.getMessage());
        }
    }

    /** Traduit le verdict du gestionnaire en message joueur. */
    private void report(Player player, String id, SuccesManager.ClaimResult result) {
        switch (result) {
            case OK:
                break; // le gestionnaire a déjà annoncé le versement
            case LOCKED:
                player.sendMessage("§cCe succès n'est pas encore débloqué.");
                break;
            case ALREADY:
                player.sendMessage("§cRécompense déjà récupérée.");
                break;
            case INVENTORY_FULL:
                player.sendMessage("§cInventaire plein — libérez de la place, la récompense vous attend.");
                break;
            case UNKNOWN:
            default:
                plugin.getLogger().warning("[Succes] " + player.getName()
                        + " a réclamé un succès inconnu : " + id);
                player.sendMessage("§cCe succès n'existe pas.");
        }
    }
}
