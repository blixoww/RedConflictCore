package fr.originsfight.bounty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Suit les quittements de faction (session courante, en mémoire).
 *
 * Si un joueur quitte sa faction et que son ex-coéquipier a une prime,
 * le kill n'est pas comptabilisé pendant les N jours configurés.
 */
public class FactionLeaveTracker implements Listener {

    /** playerUuid → (factionId → timestamp du départ) */
    private final Map<UUID, Map<String, Long>> leaveHistory = new HashMap<>();

    // ── Écoute des commandes ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String lower = event.getMessage().trim().toLowerCase();

        if (isLeaveCommand(lower) || isDisbandCommand(lower)) {
            recordLeave(event.getPlayer());
        } else {
            String kicked = extractKickedPlayer(lower, event.getMessage().trim());
            if (kicked != null) {
                Player kickedPlayer = org.bukkit.Bukkit.getPlayer(kicked);
                if (kickedPlayer != null) recordLeave(kickedPlayer);
            }
        }
    }

    private static boolean isLeaveCommand(String lower) {
        return lower.equals("/f leave") || lower.startsWith("/f leave ")
            || lower.equals("/faction leave") || lower.startsWith("/faction leave ")
            || lower.equals("/f quit") || lower.startsWith("/f quit ");
    }

    private static boolean isDisbandCommand(String lower) {
        return lower.equals("/f disband") || lower.startsWith("/f disband ")
            || lower.equals("/faction disband") || lower.startsWith("/faction disband ");
    }

    private static String extractKickedPlayer(String lower, String raw) {
        String[] prefixes = {"/f kick ", "/faction kick "};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String rest = raw.substring(prefix.length()).trim();
                String[] parts = rest.split(" ");
                return parts.length > 0 ? parts[0] : null;
            }
        }
        return null;
    }

    // ── Enregistrement ────────────────────────────────────────────────────────

    private void recordLeave(Player player) {
        String factionId = getFactionId(player);
        if (factionId == null) return;
        leaveHistory
            .computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
            .put(factionId, System.currentTimeMillis());
    }

    // ── Vérification ─────────────────────────────────────────────────────────

    public boolean recentlyLeftFaction(UUID playerUuid, String factionId, int days) {
        Map<String, Long> history = leaveHistory.get(playerUuid);
        if (history == null) return false;
        Long leaveTime = history.get(factionId);
        if (leaveTime == null) return false;
        long since = System.currentTimeMillis() - (long) days * 86_400_000L;
        return leaveTime >= since;
    }

    public static String getFactionId(Player player) {
        try {
            Class.forName("com.massivecraft.factions.FPlayers");
            com.massivecraft.factions.FPlayer fp =
                com.massivecraft.factions.FPlayers.getInstance().getByPlayer(player);
            if (fp == null) return null;
            com.massivecraft.factions.Faction fac = fp.getFaction();
            if (fac == null || fac.isWilderness()) return null;
            return fac.getId();
        } catch (Throwable ignored) { return null; }
    }
}
