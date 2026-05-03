package fr.originsfight.bounty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener central du système killstreak/prime.
 *
 * Priorité MONITOR : s'exécute après tous les autres listeners (dont KsListener)
 * pour que le kill soit déjà comptabilisé.
 */
public class BountyListener implements Listener {

    private final BountyManager    bountyManager;
    private final KillstreakManager ksManager;

    public BountyListener(BountyManager bm, KillstreakManager ksm) {
        this.bountyManager = bm;
        this.ksManager      = ksm;
    }

    // ── Mort PvP ou autre ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            // Mort PvP ─────────────────────────────────────────────────────────
            // 1. Traiter la prime sur la victime (si elle en a une)
            if (bountyManager.hasBounty(victim.getUniqueId())) {
                bountyManager.onBountyTargetKilled(victim, killer);
            }
            // 2. Réinitialiser le killstreak de la victime
            ksManager.onDeath(victim);
            // 3. Incrémenter le killstreak du tueur (déclenche paliers + seuils de prime)
            ksManager.onKill(killer);
        } else {
            // Mort non-PvP (environnement, suicide) ───────────────────────────
            // Réinitialiser le killstreak de la victime
            ksManager.onDeath(victim);
            // La prime reste active (non réclamée)
            if (bountyManager.hasBounty(victim.getUniqueId())) {
                bountyManager.onBountyTargetNonPvpDeath(victim);
            }
        }
    }

    // ── Déconnexion ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ksManager.onQuit(event.getPlayer());
    }
}
