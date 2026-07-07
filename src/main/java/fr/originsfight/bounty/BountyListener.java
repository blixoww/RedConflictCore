package fr.originsfight.bounty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener central du système killstreak/prime. Priorité MONITOR pour passer
 * après les autres listeners de mort (le kill est déjà comptabilisé).
 */
public class BountyListener implements Listener {

    private final BountyManager bountyManager;
    private final KillstreakManager ksManager;

    public BountyListener(BountyManager bountyManager, KillstreakManager ksManager) {
        this.bountyManager = bountyManager;
        this.ksManager = ksManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Mort PvP : la prime de la victime est réclamée puis les killstreaks mis
        // à jour. Mort non-PvP : la prime reste active, le streak est remis à zéro.
        if (killer != null && !killer.equals(victim)) {
            bountyManager.onBountyTargetKilled(victim, killer);
            ksManager.onDeath(victim);
            ksManager.onKill(killer);
        } else {
            ksManager.onDeath(victim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ksManager.onQuit(event.getPlayer());
    }
}
