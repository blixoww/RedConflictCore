package fr.originsfight.job;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketReader;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.List;

/**
 * Gère les packets entrants (client → serveur) sur le canal JOB_C2S.
 *
 * 0xE1 JOB_REQUEST_TOP  — String job ("ALL","MINER","FARMER","ARTISAN")
 * 0xE2 JOB_REQUEST_DATA — (pas de payload) demande un re-sync complet
 *
 * NOTE : 0xE0 JOB_CHOOSE a été supprimé — tous les métiers sont toujours actifs.
 */
public class JobServerHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:JOB_C2S";

    private static final int PKT_JOB_REQUEST_TOP        = 0xE1;
    private static final int PKT_JOB_REQUEST_DATA       = 0xE2;
    private static final int PKT_JOB_REQUEST_XP_SOURCES = 0xE3;

    private final OriginsFightCore plugin;
    private final JobManager       manager;
    private final JobPacketSender  sender;

    public JobServerHandler(OriginsFightCore plugin, JobManager manager, JobPacketSender sender) {
        this.plugin  = plugin;
        this.manager = manager;
        this.sender  = sender;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_C2S.equals(channel)) return;
        try {
            PacketReader r   = new PacketReader(message);
            int          pkt = r.readVarInt();

            switch (pkt) {
                case PKT_JOB_REQUEST_TOP: {
                    // Lecture du snapshot figé (recalculé toutes les 24h / au démarrage /
                    // via /metier topupdate) : instantané, aucun accès DB ici.
                    String jobKey = r.readString(16);
                    List<JobDatabase.TopEntry> entries = manager.getTopManager().getSnapshot(jobKey);
                    sender.sendTop(player, jobKey, entries);
                    break;
                }
                case PKT_JOB_REQUEST_DATA: {
                    JobDatabase.JobData d = manager.getData(player.getUniqueId());
                    sender.sendJobInit(player);
                    sender.sendJobData(player, d);
                    break;
                }
                case PKT_JOB_REQUEST_XP_SOURCES: {
                    sender.sendXpSources(player);
                    break;
                }
                default:
                    plugin.getLogger().warning("[Jobs] Packet C2S inconnu : 0x"
                            + Integer.toHexString(pkt) + " de " + player.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Jobs] Erreur lecture packet : " + e.getMessage());
        }
    }
}


