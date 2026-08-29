package fr.redconflict.ping;

import fr.redconflict.RedConflictCore;
import fr.redconflict.faction.FactionHook;
import fr.redconflict.friend.FriendManager;
import fr.redconflict.packets.PacketBuilder;
import fr.redconflict.packets.PacketReader;
import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Handler serveur du système de Ping.
 *
 * <p>Réception sur {@code CUSTOM:PING_C2S} : VarInt packetId (0x70 = PING_PLACE),
 * puis x/y/z en double. Diffusion sur {@code CUSTOM:PING_S2C} : VarInt packetId
 * (0x71 = PING_RECEIVE), x/y/z, byte relation (0 = faction, 1 = allié, 2 = ami),
 * String expéditeur.
 *
 * <p>Le ping n'est diffusé qu'aux joueurs du même monde, dans le rayon
 * {@link #BROADCAST_RANGE_SQ}, liés à l'expéditeur (même faction, faction
 * alliée ou ami — priorité dans cet ordre). L'expéditeur ne reçoit pas le
 * packet : son client crée le ping localement.
 */
public class PingServerHandler implements PluginMessageListener {

    /** Rayon de diffusion en blocs (128), au carré pour éviter le sqrt. */
    private static final double BROADCAST_RANGE_SQ = 128.0 * 128.0;

    private static final int PING_PLACE   = 0x70;
    private static final int PING_RECEIVE = 0x71;

    private static final String PING_C2S = "CUSTOM:PING_C2S";
    private static final String PING_S2C = "CUSTOM:PING_S2C";

    /** Relations expéditeur → destinataire portées par le packet S2C. */
    private static final int RELATION_NONE    = -1;
    private static final int RELATION_FACTION = 0;
    private static final int RELATION_ALLY    = 1;
    private static final int RELATION_FRIEND  = 2;

    private final RedConflictCore plugin;

    public PingServerHandler(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player sender, byte[] message) {
        if (!PING_C2S.equals(channel)) {
            return;
        }
        try {
            PacketReader reader = new PacketReader(message);
            if (reader.readPacketId() != PING_PLACE) {
                return;
            }
            final double x = reader.readDouble();
            final double y = reader.readDouble();
            final double z = reader.readDouble();
            plugin.getServer().getScheduler().runTask(plugin, () -> broadcastPing(sender, x, y, z));
        } catch (IOException e) {
            plugin.getLogger().warning("[Ping] Erreur lecture packet PING_PLACE de "
                    + sender.getName() + " : " + e.getMessage());
        }
    }

    private void broadcastPing(Player sender, double x, double y, double z) {
        FriendManager friendManager = FriendManager.getInstance();
        List<UUID> friends = friendManager != null ? friendManager.getFriends(sender.getUniqueId()) : null;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(sender.getUniqueId())
                    || !target.getWorld().equals(sender.getWorld())
                    || !isInRange(sender, target)) {
                continue;
            }
            int relation = resolveRelation(sender, friends, target);
            if (relation == RELATION_NONE) {
                continue;
            }
            byte[] payload = PacketBuilder.create(PING_RECEIVE)
                    .writeDouble(x)
                    .writeDouble(y)
                    .writeDouble(z)
                    .writeByte((byte) relation)
                    .writeString(sender.getName())
                    .build();
            target.sendPluginMessage(plugin, PING_S2C, payload);
        }
    }

    /**
     * Relation expéditeur → destinataire (faction, allié, puis ami). La partie
     * faction est isolée dans {@link #factionRelation} et n'est atteinte que si
     * l'intégration RedFaction est active ({@link FactionHook}), afin de ne
     * charger aucune classe faction sur un serveur sans RedFaction.
     */
    private int resolveRelation(Player sender, List<UUID> friends, Player target) {
        if (FactionHook.isEnabled()) {
            int relation = factionRelation(sender, target);
            if (relation != RELATION_NONE) {
                return relation;
            }
        }
        if (friends != null && friends.contains(target.getUniqueId())) {
            return RELATION_FRIEND;
        }
        return RELATION_NONE;
    }

    /** Seule méthode à manipuler des objets RedFaction : jamais appelée hook coupé. */
    private int factionRelation(Player sender, Player target) {
        try {
            Faction senderFaction = getFaction(sender);
            if (senderFaction == null) {
                return RELATION_NONE;
            }
            Faction targetFaction = getFaction(target);
            if (targetFaction == null) {
                return RELATION_NONE;
            }
            if (targetFaction.getId().equals(senderFaction.getId())) {
                return RELATION_FACTION;
            }
            if (senderFaction.isAlly(targetFaction.getId())) {
                return RELATION_ALLY;
            }
        } catch (Throwable ignored) {}
        return RELATION_NONE;
    }

    private boolean isInRange(Player sender, Player target) {
        return sender.getLocation().distanceSquared(target.getLocation()) <= BROADCAST_RANGE_SQ;
    }

    /** Faction (normale) du joueur via RedFaction, ou {@code null} si absent/sans faction. */
    private Faction getFaction(Player player) {
        try {
            if (!RedFactionAPI.isAvailable()) {
                return null;
            }
            Faction faction = RedFactionAPI.get().getPlayerFaction(player);
            return faction != null && faction.isNormal() ? faction : null;
        } catch (Exception e) {
            return null;
        }
    }
}
