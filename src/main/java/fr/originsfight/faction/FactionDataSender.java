package fr.originsfight.faction;

import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.redfaction.entity.Relation;
import fr.originsfight.OriginsFightCore;
import fr.originsfight.annonyme.AnonymeManager; // Import AnonymeManager
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Envoie périodiquement les données de faction des joueurs proches à chaque client.
 *
 * <p>Canal : {@code CUSTOM:FACTION_S2C}<br>
 * Format du packet :
 * <pre>
 *   VarInt  packetId       (0x80 = FACTION_DATA)
 *   String  playerName     (max 32)
 *   String  factionTag     (max 32, vide si sans faction)
 *   byte    relation       (0=own, 1=ally, 2=enemy, 3=neutral)
 * </pre>
 *
 * <p>Fréquence : toutes les {@value #INTERVAL_TICKS} ticks (2 secondes).
 * Distance maximale : {@value #RENDER_RANGE} blocs.
 */
public class FactionDataSender implements Runnable {

    private static final String FACTION_S2C    = "CUSTOM:FACTION_S2C";
    private static final int    FACTION_DATA   = 0x80;
    private static final int    INTERVAL_TICKS = 40;  // 2 secondes
    private static final double RENDER_RANGE   = 64.0;

    private final OriginsFightCore plugin;
    private final AnonymeManager anonymeManager; // Added AnonymeManager field

    public FactionDataSender(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.anonymeManager = plugin.getAnonymeManager(); // Get AnonymeManager instance
    }

    /** Lance la tâche répétée. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    @Override
    public void run() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String viewerWorld = viewer.getWorld().getName();

            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(viewer.getUniqueId())) continue;
                if (!target.getWorld().getName().equals(viewerWorld)) continue;
                if (!isInRange(viewer, target)) continue;

                String targetFaction = getFaction(target);
                // On n'envoie rien si la target n'a pas de faction
                if (targetFaction == null || targetFaction.isEmpty()) continue;

                byte relation = resolveRelation(viewer, target);

                // Le client utilise targetName comme clé de lookup dans FactionDataCache
                // (RenderPlayer fait FactionDataCache.get(entity.getName())).
                // Ne JAMAIS l'obfusquer ici, sinon l'entrée n'est jamais retrouvée et
                // l'ancien tag réel reste affiché jusqu'à expiration (10s).
                String targetName = target.getName();
                if (anonymeManager.isAnonymous(target) && !viewer.hasPermission("staff.annonyme")) {
                    targetFaction = "Faction masquée";
                }

                plugin.getLogger().fine("[FactionData] " + viewer.getName() + " -> " + target.getName()
                        + " faction=" + targetFaction + " relation=" + relation);

                byte[] payload = PacketBuilder.create(FACTION_DATA)
                        .writeString(safeTrunc(targetName, 32))
                        .writeString(safeTrunc(targetFaction, 32))
                        .writeByte(relation)
                        .build();

                viewer.sendPluginMessage(plugin, FACTION_S2C, payload);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Détermine la relation du viewer envers la target.
     * Valeurs retournées : 0=own/member, 1=ally/trêve, 2=enemy, 3=neutral
     */
    private byte resolveRelation(Player viewer, Player target) {
        try {
            if (!RedFactionAPI.isAvailable()) return 3;
            RedFactionAPI api = RedFactionAPI.get();

            Faction fViewer = api.getPlayerFaction(viewer);
            Faction fTarget = api.getPlayerFaction(target);
            if (fViewer == null || fTarget == null) return 3;

            Relation rel = api.getRelation(fViewer, fTarget);
            if (rel == null) return 3;
            switch (rel) {
                case SELF:  return 0;
                case ALLY:  return 1;
                case TRUCE: return 1;
                case ENEMY: return 2;
                default:    return 3; // NEUTRAL
            }
        } catch (Exception ignored) {}
        return 3;
    }

    private boolean isInRange(Player a, Player b) {
        double dx = a.getLocation().getX() - b.getLocation().getX();
        double dz = a.getLocation().getZ() - b.getLocation().getZ();
        double dy = a.getLocation().getY() - b.getLocation().getY();
        return dx*dx + dy*dy + dz*dz <= RENDER_RANGE * RENDER_RANGE;
    }

    private String safeTrunc(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intégration RedFaction
    // ─────────────────────────────────────────────────────────────────────────

    private String getFaction(Player player) {
        try {
            if (!RedFactionAPI.isAvailable()) return null;
            Faction faction = RedFactionAPI.get().getPlayerFaction(player);
            if (faction == null || !faction.isNormal()) return null;
            String tag = faction.getTag();
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Exception ignored) {}
        return null;
    }
}
