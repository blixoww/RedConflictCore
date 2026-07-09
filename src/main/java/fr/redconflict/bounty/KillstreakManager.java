package fr.redconflict.bounty;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Killstreaks en mémoire. Quand un joueur atteint un seuil configuré
 * ({@code bounty.thresholds} de config.yml), le listener de seuil est notifié
 * (câblé sur {@link BountyManager#onThresholdCrossed} par le constructeur du manager).
 */
public class KillstreakManager {

    /** Notifié quand un joueur franchit un seuil de killstreak. */
    public interface ThresholdListener {
        void onThresholdCrossed(Player player, int killstreak, int thresholdIndex);
    }

    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final List<Integer> bountyThresholdKills = new ArrayList<>();
    private boolean resetOnDisconnect = true;
    private ThresholdListener thresholdListener;

    public void loadConfig(JavaPlugin plugin) {
        resetOnDisconnect = plugin.getConfig().getBoolean("bounty.reset-on-disconnect", true);
        for (Map<?, ?> entry : plugin.getConfig().getMapList("bounty.thresholds")) {
            bountyThresholdKills.add(((Number) entry.get("killstreak")).intValue());
        }
        bountyThresholdKills.sort(Integer::compareTo);
    }

    public void setThresholdListener(ThresholdListener listener) {
        this.thresholdListener = listener;
    }

    public int onKill(Player killer) {
        int streak = streaks.getOrDefault(killer.getUniqueId(), 0) + 1;
        streaks.put(killer.getUniqueId(), streak);

        int thresholdIndex = bountyThresholdKills.indexOf(streak);
        if (thresholdIndex >= 0 && thresholdListener != null) {
            thresholdListener.onThresholdCrossed(killer, streak, thresholdIndex);
        }
        return streak;
    }

    /** Remet le killstreak à zéro. @return la valeur avant remise à zéro. */
    public int onDeath(Player victim) {
        Integer old = streaks.remove(victim.getUniqueId());
        return old != null ? old : 0;
    }

    public void onQuit(Player player) {
        if (resetOnDisconnect) {
            streaks.remove(player.getUniqueId());
        }
    }

    public int getStreak(UUID uuid) {
        return streaks.getOrDefault(uuid, 0);
    }

    public List<Integer> getBountyThresholdKills() {
        return Collections.unmodifiableList(bountyThresholdKills);
    }
}
