package fr.redconflict.faction;

import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.redconflict.RedConflictCore;
import fr.redconflict.friend.FriendManager;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    private final RedConflictCore plugin;

    /** Viewers ayant reçu au moins un joueur au tick précédent (pour vider proprement). */
    private final Set<UUID> hadPlayers = new HashSet<>();

    public MinimapPositionSender(RedConflictCore plugin) {
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

            List<UUID> friends = (friendManager != null) ? friendManager.getFriends(viewerUUID) : null;

            // Candidats à portée d'abord, relations ensuite : la partie faction est
            // isolée dans resolveFactionRelations et n'est pas exécutée quand le hook
            // RedFaction est coupé (Minage), donc aucune classe faction n'est chargée.
            List<Player> candidates = new ArrayList<>();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(viewerUUID)) continue;
                if (!target.getWorld().getName().equals(viewerWorld)) continue;
                if (!isInRange(viewer, target)) continue;
                candidates.add(target);
            }

            // Collecter les cibles visibles (relation ∈ {0,1,2}) avant sérialisation.
            int[] factionRelations = resolveFactionRelations(viewer, candidates);
            List<Player> targets = new ArrayList<>();
            List<Integer> relations = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                Player target = candidates.get(i);
                int relation = factionRelations[i]; // -1 = non visible
                if (relation == -1 && isFriend(friends, target)) relation = 2;

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
    // Intégration RedFaction — identique à PingServerHandler pour rester cohérent.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Relation faction du viewer envers chaque candidat, dans l'ordre de la liste :
     * 0 = même faction, 1 = allié, -1 = aucune. Renvoie uniquement des -1 quand
     * l'intégration RedFaction est coupée ({@link FactionHook}).
     */
    private int[] resolveFactionRelations(Player viewer, List<Player> candidates) {
        int[] relations = new int[candidates.size()];
        Arrays.fill(relations, -1);
        if (!FactionHook.isEnabled()) return relations;
        try {
            fillFactionRelations(viewer, candidates, relations);
        } catch (Throwable ignored) {}
        return relations;
    }

    /** Seule méthode à manipuler des objets RedFaction : jamais appelée hook coupé. */
    private void fillFactionRelations(Player viewer, List<Player> candidates, int[] relations) {
        Faction viewerFaction = getFaction(viewer);
        if (viewerFaction == null) return;
        for (int i = 0; i < candidates.size(); i++) {
            Faction targetFaction = getFaction(candidates.get(i));
            if (targetFaction == null) continue;
            if (targetFaction.getId().equals(viewerFaction.getId())) relations[i] = 0;
            else if (viewerFaction.isAlly(targetFaction.getId())) relations[i] = 1;
        }
    }

    /** Faction (normale) du joueur, ou {@code null} s'il n'en a pas / RedFaction absent. */
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
