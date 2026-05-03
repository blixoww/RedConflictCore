package fr.originsfight.bounty;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Suit les killstreaks en mémoire et déclenche les primes automatiques.
 */
public class KillstreakManager {

    private static KillstreakManager instance;

    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final List<Integer> bountyThresholdKills = new ArrayList<>();
    private boolean resetOnDisconnect = true;

    public KillstreakManager() { instance = this; }
    public static KillstreakManager getInstance() { return instance; }

    public void loadConfig(JavaPlugin plugin) {
        resetOnDisconnect = plugin.getConfig().getBoolean("bounty.reset-on-disconnect", true);

        List<Map<?, ?>> thresholdList = plugin.getConfig().getMapList("bounty.thresholds");
        for (Map<?, ?> entry : thresholdList) {
            bountyThresholdKills.add(((Number) entry.get("killstreak")).intValue());
        }
        bountyThresholdKills.sort(Integer::compareTo);
    }

    public int onKill(Player killer) {
        int streak = streaks.getOrDefault(killer.getUniqueId(), 0) + 1;
        streaks.put(killer.getUniqueId(), streak);

        int threshIdx = bountyThresholdKills.indexOf(streak);
        if (threshIdx >= 0) {
            BountyManager.getInstance().onThresholdCrossed(killer, streak, threshIdx);
        }

        return streak;
    }

    public int onDeath(Player victim) {
        int old = streaks.getOrDefault(victim.getUniqueId(), 0);
        streaks.remove(victim.getUniqueId());
        return old;
    }

    public void onQuit(Player player) {
        if (resetOnDisconnect) streaks.remove(player.getUniqueId());
    }

    public int getStreak(UUID uuid) { return streaks.getOrDefault(uuid, 0); }
    public List<Integer> getBountyThresholdKills() { return Collections.unmodifiableList(bountyThresholdKills); }
}
