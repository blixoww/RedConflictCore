package fr.redconflict.faction;

import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.redfaction.entity.Relation;
import fr.redconflict.RedConflictCore;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Envoie au client la faction propriétaire du chunk courant.
 *
 * <p>Canal : {@code CUSTOM:FACTION_S2C}<br>
 * Packet id : {@code 0x81 = FACTION_ZONE}<br>
 * Format :
 * <pre>
 *   VarInt  packetId       (0x81)
 *   String  factionName    (max 64, vide = wilderness)
 *   byte    relation       (0=own, 1=ally, 2=truce, 3=enemy, 4=neutral)
 *   String  ownFaction     (max 64, tag de la propre faction du joueur, vide = sans faction)
 * </pre>
 *
 * <p>Déclencheurs : changement de chunk (via e.getTo()), connexion, respawn,
 * téléportation, + tâche périodique toutes les 20 ticks pour les claims
 * modifiés sans déplacement du joueur.
 */
public class FactionZoneSender implements Listener {

    private static final String FACTION_S2C  = "CUSTOM:FACTION_S2C";
    private static final int    FACTION_ZONE = 0x81;

    private final RedConflictCore plugin;
    /** Cache de la dernière donnée envoyée : UUID → "owner|rel|own" */
    private final Map<UUID, String> lastSent = new ConcurrentHashMap<>();

    public FactionZoneSender(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Événements
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        // Seulement sur changement de chunk
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4
                && from.getBlockZ() >> 4 == to.getBlockZ() >> 4) return;
        // Utiliser e.getTo() : c'est la DESTINATION, pas player.getLocation()
        // qui pointe encore vers l'ancien chunk pendant l'événement
        sendZone(e.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> sendZone(e.getPlayer(), null), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Location dest = e.getRespawnLocation();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> sendZone(e.getPlayer(), dest), 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location dest = e.getTo();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> sendZone(e.getPlayer(), dest), 2L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tâche périodique — mise à jour toutes les 20 ticks (1 s)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * À appeler après l'enregistrement du listener.
     * Détecte les changements de zone sans déplacement de chunk
     * (ex : /f claim en temps réel, faction dissoute, etc.)
     */
    public void startPeriodicUpdate() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                sendZoneIfChanged(p, p.getLocation());
            }
        }, 20L, 20L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envoi du packet
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envoie la zone uniquement si elle a changé depuis le dernier envoi.
     * Utilisée par la tâche périodique pour éviter le spam.
     */
    private void sendZoneIfChanged(Player player, Location location) {
        if (location == null) location = player.getLocation();
        String ownFaction   = getOwnFactionTag(player);
        String ownerFaction = getClaimOwner(location);
        if (ownerFaction == null) ownerFaction = "";
        int relation = ownerFaction.isEmpty() ? 4 : resolveRelation(player, ownerFaction);
        String key = ownerFaction + "|" + relation + "|" + ownFaction;
        if (!key.equals(lastSent.get(player.getUniqueId()))) {
            sendZoneDirect(player, ownerFaction, relation, ownFaction);
            lastSent.put(player.getUniqueId(), key);
        }
    }

    /**
     * Envoi inconditionnel (utilisé sur changement de chunk, connexion, etc.)
     *
     * @param location  Position de DESTINATION (e.getTo()). Si null → player.getLocation().
     */
    public void sendZone(Player player, Location location) {
        if (location == null) location = player.getLocation();
        String ownFaction   = getOwnFactionTag(player);
        String ownerFaction = getClaimOwner(location);
        if (ownerFaction == null) ownerFaction = "";
        int relation = ownerFaction.isEmpty() ? 4 : resolveRelation(player, ownerFaction);
        sendZoneDirect(player, ownerFaction, relation, ownFaction);
        lastSent.put(player.getUniqueId(), ownerFaction + "|" + relation + "|" + ownFaction);
    }

    /** Construit et envoie le packet plugin-message. */
    private void sendZoneDirect(Player player, String ownerFaction, int relation, String ownFaction) {
        byte[] pkt = PacketBuilder.create(FACTION_ZONE)
                .writeString(safeTrunc(ownerFaction, 64))
                .writeByte((byte) relation)
                .writeString(safeTrunc(ownFaction,   64))
                .build();
        player.sendPluginMessage(plugin, FACTION_S2C, pkt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Claim owner — API RedFaction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne le tag de la faction qui possède le chunk à cette position,
     * ou {@code null} si c'est la Wilderness / SafeZone / WarZone.
     */
    private String getClaimOwner(Location location) {
        try {
            if (!RedFactionAPI.isAvailable()) return null;
            Faction faction = RedFactionAPI.get().getFactionAt(location);

            if (faction == null)        return null;
            if (!faction.isNormal())    return null; // SafeZone / WarZone

            String tag = faction.getTag();
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Exception e) {
            plugin.getLogger().warning("[FactionZone] getClaimOwner erreur: " + e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Propre faction du joueur
    // ─────────────────────────────────────────────────────────────────────────

    private String getOwnFactionTag(Player player) {
        try {
            if (!RedFactionAPI.isAvailable()) return "";
            Faction f = RedFactionAPI.get().getPlayerFaction(player);
            if (f == null) return "";
            String tag = f.getTag();
            return (tag != null) ? tag : "";
        } catch (Exception e) {
            plugin.getLogger().warning("[FactionZone] getOwnFactionTag erreur: " + e.getMessage());
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Résolution de la relation joueur ↔ faction owner
    // ─────────────────────────────────────────────────────────────────────────

    private int resolveRelation(Player player, String ownerTag) {
        try {
            if (!RedFactionAPI.isAvailable()) return 4;
            RedFactionAPI api = RedFactionAPI.get();

            Faction pFaction = api.getPlayerFaction(player);
            if (pFaction == null) return 4;

            if (ownerTag.equals(pFaction.getTag())) return 0;

            Faction ownerFaction = findFactionByTag(ownerTag);
            if (ownerFaction == null) return 4;

            Relation rel = api.getRelation(pFaction, ownerFaction);
            if (rel == null) return 4;

            switch (rel) {
                case SELF:    return 0;
                case ALLY:    return 1;
                case TRUCE:   return 2;
                case ENEMY:   return 3;
                default:      return 4;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[FactionZone] resolveRelation erreur: " + e.getMessage());
        }
        return 4;
    }

    private Faction findFactionByTag(String tag) {
        try {
            if (!RedFactionAPI.isAvailable()) return null;
            for (Faction f : RedFactionAPI.get().getAllFactions()) {
                if (tag.equals(f.getTag())) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaire
    // ─────────────────────────────────────────────────────────────────────────

    private String safeTrunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
