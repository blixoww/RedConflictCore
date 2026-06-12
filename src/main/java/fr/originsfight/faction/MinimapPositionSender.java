package fr.originsfight.faction;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.friend.FriendManager;
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tâche serveur qui envoie périodiquement, à chaque client, les positions des joueurs
 * qu'il a le droit de voir sur sa minimap : <b>uniquement</b> les membres de sa faction,
 * ses alliés et ses amis.
 *
 * <p><b>Sécurité PvP — critique :</b> ce sender filtre les destinataires <b>avant</b>
 * l'envoi. Aucune position d'ennemi ou de joueur neutre n'est jamais sérialisée ni
 * transmise. Un client modifié ne peut donc pas afficher d'ennemis sur sa minimap :
 * la donnée n'existe pas côté client. La détection d'ennemis reste réservée à {@code /near}.
 *
 * <p>Canal : {@code CUSTOM:MMAP_S2C}. Packet {@code MINIMAP_PLAYERS} (0x01) :
 * <pre>
 *   VarInt packetId (0x01)
 *   VarInt count
 *   count × ( String name | double x | double y | double z
 *           | byte relation (0=faction,1=allié,2=ami) | byte yaw (deg*256/360) )
 * </pre>
 *
 * <p>La logique de relation est identique à celle de {@code PingServerHandler}.
 */
public class MinimapPositionSender implements Runnable {

    /** Intervalle d'envoi en ticks (10 = 0,5 s). Doit rester ≤ l'expiration client (~3 s). */
    private static final long INTERVAL_TICKS = 10L;

    /** Packet id minimap (local au canal MMAP_S2C). */
    private static final int MINIMAP_PLAYERS = 0x01;

    private static final String MMAP_S2C = "CUSTOM:MMAP_S2C";

    /**
     * Rayon de diffusion au carré. 160 blocs ≥ rayon maximal de la minimap côté client
     * (112 blocs) afin que les marqueurs apparaissent un peu avant le bord visible.
     */
    private static final double RANGE_SQ = 160.0 * 160.0;

    private final OriginsFightCore plugin;

    /** Viewers ayant reçu au moins un joueur au tick précédent (pour vider proprement). */
    private final Set<UUID> hadPlayers = new HashSet<>();

    public MinimapPositionSender(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    @Override
    public void run() {
        FriendManager friendManager = FriendManager.getInstance();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID   viewerUUID  = viewer.getUniqueId();
            String viewerWorld = viewer.getWorld().getName();

            String viewerFaction = getFaction(viewer);
            List<String> viewerAllies = getAllies(viewerFaction);
            List<UUID> friends = (friendManager != null) ? friendManager.getFriends(viewerUUID) : null;

            // Collecter les cibles visibles (relation ∈ {0,1,2}) avant sérialisation.
            List<Player> targets = new ArrayList<>();
            List<Integer> relations = new ArrayList<>();

            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(viewerUUID)) continue;
                if (!target.getWorld().getName().equals(viewerWorld)) continue;
                if (!isInRange(viewer, target)) continue;

                int relation = -1; // -1 = non visible
                if (isSameFaction(viewerFaction, target)) relation = 0;
                else if (isAlly(viewerAllies, target)) relation = 1;
                else if (isFriend(friends, target)) relation = 2;

                if (relation != -1) {
                    targets.add(target);
                    relations.add(relation);
                }
            }

            int count = targets.size();
            if (count == 0) {
                // Envoyer un snapshot vide une seule fois pour effacer immédiatement la
                // minimap du viewer, puis cesser d'émettre (le cache client expirera seul).
                if (hadPlayers.remove(viewerUUID)) {
                    viewer.sendPluginMessage(plugin, MMAP_S2C,
                            PacketBuilder.create(MINIMAP_PLAYERS).writeVarInt(0).build());
                }
                continue;
            }

            PacketBuilder pb = PacketBuilder.create(MINIMAP_PLAYERS).writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Player t = targets.get(i);
                Location loc = t.getLocation();
                pb.writeString(safeTrunc(t.getName(), 32))
                  .writeDouble(loc.getX())
                  .writeDouble(loc.getY())
                  .writeDouble(loc.getZ())
                  .writeByte((byte) (int) relations.get(i))
                  .writeByte(encodeYaw(loc.getYaw()));
            }
            viewer.sendPluginMessage(plugin, MMAP_S2C, pb.build());
            hadPlayers.add(viewerUUID);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Encode le yaw (degrés) sur un octet : norm dans [0,360) → [0,256). */
    private static byte encodeYaw(float yaw) {
        float norm = ((yaw % 360.0f) + 360.0f) % 360.0f;
        return (byte) (((int) (norm * 256.0f / 360.0f)) & 0xFF);
    }

    private static String safeTrunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private boolean isInRange(Player viewer, Player target) {
        double dx = viewer.getLocation().getX() - target.getLocation().getX();
        double dy = viewer.getLocation().getY() - target.getLocation().getY();
        double dz = viewer.getLocation().getZ() - target.getLocation().getZ();
        return (dx * dx + dy * dy + dz * dz) <= RANGE_SQ;
    }

    private boolean isFriend(List<UUID> friends, Player target) {
        return friends != null && friends.contains(target.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intégration Factions (réflexion, sans dépendance compile-time) — identique
    // à PingServerHandler pour rester cohérent.
    // ─────────────────────────────────────────────────────────────────────────

    private String getFaction(Player player) {
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
            Map<?, ?> map = (Map<?, ?>) relations;
            List<String> allies = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String rel = e.getValue().toString();
                if (rel.equalsIgnoreCase("ALLY")) allies.add(e.getKey().toString());
            }
            return allies;
        } catch (Exception ignored) {}
        return java.util.Collections.emptyList();
    }

    private boolean isSameFaction(String viewerFaction, Player target) {
        if (viewerFaction == null) return false;
        return viewerFaction.equals(getFaction(target));
    }

    private boolean isAlly(List<String> viewerAllies, Player target) {
        if (viewerAllies == null || viewerAllies.isEmpty()) return false;
        String targetFaction = getFaction(target);
        return targetFaction != null && viewerAllies.contains(targetFaction);
    }
}
