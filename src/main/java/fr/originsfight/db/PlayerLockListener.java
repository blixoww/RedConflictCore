package fr.originsfight.db;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Logger;

/**
 * Verrou de présence cross-serveur + synchronisation inventaire/enderchest.
 *
 * <p>Join : acquiert le verrou, puis charge et applique l'inventaire + enderchest depuis H2.
 * Quit : sauvegarde l'inventaire + enderchest dans H2 (synchrone) AVANT de libérer le verrou,
 * garantissant que les données sont persistées avant que le joueur n'arrive sur un autre serveur.
 *
 * <p>{@code syncService} peut être {@code null} si la synchro d'inventaire est désactivée
 * ({@code database.sync.enabled: false}) ; dans ce cas seul le verrou est géré.
 */
public class PlayerLockListener implements Listener {

    private static final Logger LOG = Logger.getLogger("PlayerLock");

    private final PlayerLockService lockService;
    private final PlayerDataSyncService syncService; // nullable
    private final String serverId;
    private final boolean kickOnConflict;

    public PlayerLockListener(PlayerLockService lockService, PlayerDataSyncService syncService,
                              String serverId, boolean kickOnConflict) {
        this.lockService = lockService;
        this.syncService = syncService;
        this.serverId = serverId;
        this.kickOnConflict = kickOnConflict;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        boolean acquired = lockService.acquire(p.getUniqueId(), serverId);
        if (!acquired) {
            LOG.warning("[Lock] " + p.getName() + " est encore verrouillé sur un autre serveur "
                    + "(données potentiellement non sauvegardées).");
            if (kickOnConflict) {
                p.kickPlayer("§cTransfert en cours...\n§7Tes données sont en cours de sauvegarde sur l'autre serveur.\n§7Reconnecte-toi dans quelques secondes.");
                return; // ne pas charger : le joueur est expulsé
            }
        }
        if (syncService != null) {
            try {
                syncService.loadAndApply(p);
            } catch (Exception e) {
                LOG.severe("[Sync] Chargement inventaire " + p.getName() + " échoué : " + e.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (syncService != null) {
            try {
                syncService.saveNow(p);
            } catch (Exception e) {
                LOG.severe("[Sync] Sauvegarde inventaire " + p.getName() + " échouée : " + e.getMessage());
            }
        }
        lockService.release(p.getUniqueId(), serverId);
    }
}
