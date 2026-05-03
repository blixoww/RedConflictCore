package fr.originsfight.faction;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.annonyme.AnonymeManager; // Import AnonymeManager
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor; // Import ChatColor
import org.bukkit.entity.Player;

import java.util.UUID;

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
                    targetFaction = ChatColor.MAGIC.toString() + "FACTION" + ChatColor.RESET.toString();
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
     * Valeurs retournées : 0=own/member, 1=ally, 2=enemy, 3=neutral/truce
     */
    private byte resolveRelation(Player viewer, Player target) {
        try {
            Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
            Object   fpAll   = fpClass.getMethod("getInstance").invoke(null);

            Object fpViewer = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, viewer);
            Object fpTarget = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, target);
            if (fpViewer == null || fpTarget == null) return 3;

            // Vérification "même faction" via comparaison des objets Faction
            Object fViewer = fpViewer.getClass().getMethod("getFaction").invoke(fpViewer);
            Object fTarget = fpTarget.getClass().getMethod("getFaction").invoke(fpTarget);
            if (fViewer != null && fTarget != null) {
                try {
                    String idV = (String) fViewer.getClass().getMethod("getId").invoke(fViewer);
                    String idT = (String) fTarget.getClass().getMethod("getId").invoke(fTarget);
                    if (idV != null && idV.equals(idT)) return 0;
                } catch (Exception ignored) {}
            }

            // Chercher getRelationTo dynamiquement (évite le problème du type de paramètre)
            for (java.lang.reflect.Method m : fpViewer.getClass().getMethods()) {
                if (!m.getName().equals("getRelationTo")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                try {
                    Object rel = m.invoke(fpViewer, fpTarget);
                    if (rel == null) break;
                    String relName = ((Enum<?>) rel).name().toUpperCase();
                    if (relName.equals("MEMBER")) return 0;
                    if (relName.equals("ALLY"))   return 1;
                    if (relName.equals("TRUCE"))  return 1;
                    if (relName.equals("ENEMY"))  return 2;
                    return 3; // NEUTRAL, TRUCE, etc.
                } catch (Exception ignored) {}
                break;
            }
        } catch (Exception ignored) {}

        // Fallback : comparaison de tags
        String viewerFaction = getFaction(viewer);
        String targetFaction = getFaction(target);
        if (viewerFaction == null || targetFaction == null) return 3;
        if (viewerFaction.equals(targetFaction)) return 0;
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
    // Intégration Factions via reflection (pas de dépendance compile-time)
    // ─────────────────────────────────────────────────────────────────────────

    private String getFaction(Player player) {
        // MassiveCore / FactionsUUID
        try {
            Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
            Object   fpAll   = fpClass.getMethod("getInstance").invoke(null);
            Object   fp      = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, player);
            if (fp == null) return null;
            Object faction = fp.getClass().getMethod("getFaction").invoke(fp);
            if (faction == null) return null;
            Boolean safe = tryBoolean(faction, "isSafeZone");
            Boolean war  = tryBoolean(faction, "isWarZone");
            if (Boolean.TRUE.equals(safe) || Boolean.TRUE.equals(war)) return null;
            // Wilderness / no faction
            Boolean wild = tryBoolean(faction, "isWilderness");
            if (Boolean.TRUE.equals(wild)) return null;
            String tag = (String) faction.getClass().getMethod("getTag").invoke(faction);
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Exception ignored) {}

        // Factions legacy
        try {
            Class<?> mpClass = Class.forName("com.massivecraft.factions.entity.MPlayer");
            Object   mp      = mpClass.getMethod("get", UUID.class).invoke(null, player.getUniqueId());
            if (mp == null) return null;
            Object faction = mp.getClass().getMethod("getFaction").invoke(mp);
            if (faction == null) return null;
            return (String) faction.getClass().getMethod("getName").invoke(faction);
        } catch (Exception ignored) {}

        return null;
    }

    private Boolean tryBoolean(Object obj, String methodName) {
        try {
            return (Boolean) obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
