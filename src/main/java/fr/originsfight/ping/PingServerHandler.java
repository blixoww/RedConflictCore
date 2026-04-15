package fr.originsfight.ping;

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

        String senderFaction = getFaction(sender);
        List<String> senderAllies = getAllies(senderFaction);

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(senderUUID)) continue;
            if (!target.getWorld().getName().equals(senderWorld)) continue;
            if (!isInRange(sender, target)) continue;

            // Déterminer la relation spécifique au target (priorité : faction > ally > friend)
            int relation = -1; // -1 = none
            if (isSameFaction(senderFaction, target)) relation = 0;
            else if (isAlly(senderAllies, target)) relation = 1;
            else if (isFriend(friends, target)) relation = 2;

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
    // Intégration Factions (Massivecraft/HCFactions via reflection pour rester
    // compatible sans dépendance compile-time).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Récupère le nom de faction du joueur.
     * Supporte : MassiveCoreFactions, HCFactions (FactionsUUID), Factions-0.7.x.
     * Retourne {@code null} si aucun plugin de faction n'est trouvé ou si le joueur
     * est sans faction.
     */
    private String getFaction(Player player) {
        // Tentative MassiveCore / FactionsUUID
        try {
            Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
            Object fpAll = fpClass.getMethod("getInstance").invoke(null);
            Object fp = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, player);
            if (fp == null) return null;
            Object faction = fp.getClass().getMethod("getFaction").invoke(fp);
            if (faction == null) return null;
            String tag = (String) faction.getClass().getMethod("getTag").invoke(faction);
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Exception ignored) {}

        // Tentative Factions legacy (org.bukkit plugin)
        try {
            Class<?> factionsClass = Class.forName("com.massivecraft.factions.entity.MPlayer");
            Object mp = factionsClass.getMethod("get", UUID.class).invoke(null, player.getUniqueId());
            if (mp == null) return null;
            Object faction = mp.getClass().getMethod("getFaction").invoke(mp);
            if (faction == null) return null;
            return (String) faction.getClass().getMethod("getName").invoke(faction);
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Retourne la liste des noms de factions alliées à {@code factionName}.
     */
    @SuppressWarnings("unchecked")
    private List<String> getAllies(String factionName) {
        if (factionName == null) return java.util.Collections.emptyList();
        try {
            Class<?> factionsClass = Class.forName("com.massivecraft.factions.Factions");
            Object factionsAll = factionsClass.getMethod("getInstance").invoke(null);
            Object faction = factionsAll.getClass().getMethod("getByTag", String.class)
                    .invoke(factionsAll, factionName);
            if (faction == null) return java.util.Collections.emptyList();
            Object relations = faction.getClass().getMethod("getRelationWishes").invoke(faction);
            // getRelationWishes retourne Map<String, Relation>
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) relations;
            List<String> allies = new java.util.ArrayList<>();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                String rel = e.getValue().toString();
                if (rel.equalsIgnoreCase("ALLY")) allies.add(e.getKey().toString());
            }
            return allies;
        } catch (Exception ignored) {}
        return java.util.Collections.emptyList();
    }

    private boolean isSameFaction(String senderFaction, Player target) {
        if (senderFaction == null) return false;
        String targetFaction = getFaction(target);
        return senderFaction.equals(targetFaction);
    }

    private boolean isAlly(List<String> senderAllies, Player target) {
        if (senderAllies == null || senderAllies.isEmpty()) return false;
        String targetFaction = getFaction(target);
        return targetFaction != null && senderAllies.contains(targetFaction);
    }
}

