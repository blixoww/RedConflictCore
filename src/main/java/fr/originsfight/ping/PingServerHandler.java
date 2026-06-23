package fr.originsfight.ping;

import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.originsfight.OriginsFightCore;
import fr.originsfight.friend.FriendManager;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.packets.PacketReader;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Handler serveur du système de Ping.
 *
 * <p>Réception : canal {@code CUSTOM:PING_C2S}
 * <pre>
 *   VarInt packetId (0x70 = PING_PLACE)
 *   double x
 *   double y
 *   double z
 *   byte   typeOrdinal
 * </pre>
 *
 * <p>Diffusion : canal {@code CUSTOM:PING_S2C} vers les joueurs éligibles
 * <pre>
 *   VarInt packetId (0x71 = PING_RECEIVE)
 *   double x
 *   double y
 *   double z
 *   byte   typeOrdinal
 *   String sender (max 64 chars)
 * </pre>
 *
 * <p>Critères de diffusion (les trois conditions sont réunies en OR) :
 * <ul>
 *   <li>Même faction que l'expéditeur <b>ET</b> dans le rayon configuré</li>
 *   <li>Faction alliée de l'expéditeur <b>ET</b> dans le rayon</li>
 *   <li>Dans la liste d'amis de l'expéditeur (FriendManager) <b>ET</b> dans le rayon</li>
 * </ul>
 * Le rayon est défini par {@link #BROADCAST_RANGE_SQ} (256 blocs² par défaut → 16 blocs).
 * L'expéditeur lui-même ne reçoit pas le packet S2C (son client crée le ping localement).
 */
public class PingServerHandler implements PluginMessageListener {

    /** Rayon de diffusion en blocs (au carré pour éviter sqrt). */
    private static final double BROADCAST_RANGE_SQ = 128.0 * 128.0;

    /** Packet ID PING_PLACE côté client → serveur. */
    private static final int PING_PLACE   = 0x70;
    /** Packet ID PING_RECEIVE côté serveur → client. */
    private static final int PING_RECEIVE = 0x71;

    private static final String PING_C2S = "CUSTOM:PING_C2S";
    private static final String PING_S2C = "CUSTOM:PING_S2C";

    private final OriginsFightCore plugin;

    public PingServerHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player sender, byte[] message) {
        if (!PING_C2S.equals(channel)) return;

        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            if (packetId != PING_PLACE) return;

            double x = reader.readDouble();
            double y = reader.readDouble();
            double z = reader.readDouble();
            // Plus de typeOrdinal dans le nouveau format

            final double fx = x, fy = y, fz = z;
            plugin.getServer().getScheduler().runTask(plugin,
                () -> broadcastPing(sender, fx, fy, fz));

        } catch (IOException e) {
            plugin.getLogger().warning("[PingSystem] Erreur lecture packet PING_PLACE de "
                + sender.getName() + " : " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void broadcastPing(Player sender, double x, double y, double z) {
        String senderName  = sender.getName();
        UUID   senderUUID  = sender.getUniqueId();
        String senderWorld = sender.getWorld().getName();

        FriendManager friendManager = FriendManager.getInstance();
        List<UUID> friends = (friendManager != null) ? friendManager.getFriends(senderUUID) : null;

        // Le payload est construit par destination ci-dessous (inclut le typeOrdinal)

        Faction senderFaction = getFaction(sender);

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(senderUUID)) continue;
            if (!target.getWorld().getName().equals(senderWorld)) continue;
            if (!isInRange(sender, target)) continue;

            // Déterminer la relation spécifique au target (priorité : faction > ally > friend)
            int relation = -1; // -1 = none
            if (senderFaction != null) {
                Faction targetFaction = getFaction(target);
                if (targetFaction != null) {
                    if (targetFaction.getId().equals(senderFaction.getId())) relation = 0;
                    else if (senderFaction.isAlly(targetFaction.getId())) relation = 1;
                }
            }
            if (relation == -1 && isFriend(friends, target)) relation = 2;

            if (relation != -1) {
                if (relation == 2) {
                    plugin.getLogger().info("[Ping] Envoi PING (friend) de " + senderName + " vers " + target.getName());
                }
                // Construire un payload spécifique en incluant le typeOrdinal
                byte[] payloadWithType = PacketBuilder.create(PING_RECEIVE)
                        .writeDouble(x)
                        .writeDouble(y)
                        .writeDouble(z)
                        .writeByte((byte) relation)
                        .writeString(senderName)
                        .build();
                target.sendPluginMessage(plugin, PING_S2C, payloadWithType);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers portée & relations
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isInRange(Player sender, Player target) {
        double dx = sender.getLocation().getX() - target.getLocation().getX();
        double dy = sender.getLocation().getY() - target.getLocation().getY();
        double dz = sender.getLocation().getZ() - target.getLocation().getZ();
        return (dx*dx + dy*dy + dz*dz) <= BROADCAST_RANGE_SQ;
    }

    private boolean isFriend(List<UUID> friends, Player target) {
        return friends != null && friends.contains(target.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intégration RedFaction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Récupère la faction (normale) du joueur via l'API RedFaction.
     * Retourne {@code null} si RedFaction est absent ou si le joueur est sans faction.
     */
    private Faction getFaction(Player player) {
        try {
            if (!RedFactionAPI.isAvailable()) return null;
            Faction faction = RedFactionAPI.get().getPlayerFaction(player);
            if (faction == null || !faction.isNormal()) return null;
            return faction;
        } catch (Exception ignored) {}
        return null;
    }
}

