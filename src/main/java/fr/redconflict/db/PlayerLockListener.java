package fr.redconflict.db;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Verrou de présence cross-serveur + synchronisation inventaire/enderchest.
 *
 * <p><b>Pré-login : on attend le serveur précédent.</b> C'est le point clé du
 * transfert. Quand Velocity fait passer un joueur du Faction au Minage, il
 * ouvre la connexion au Minage <i>avant</i> de fermer celle du Faction : le
 * {@link PlayerJoinEvent} du serveur d'arrivée précède donc le
 * {@link PlayerQuitEvent} du serveur de départ, et l'inventaire chargé serait
 * celui d'<i>avant</i> le transfert — le joueur verrait ses deux serveurs
 * diverger tout seuls. Le verrou est donc pris dans
 * {@link AsyncPlayerPreLoginEvent}, qui s'exécute hors du thread principal
 * pendant que le joueur est encore sur l'écran de connexion : on peut y
 * attendre sans figer le serveur, et l'attente s'arrête dès que l'autre serveur
 * a sauvegardé puis relâché (la sauvegarde du quit précède la libération).
 *
 * <p>Join : le verrou est déjà à nous, il ne reste qu'à charger et appliquer
 * l'inventaire + enderchest depuis H2.
 * Quit : sauvegarde synchrone dans H2 AVANT de libérer le verrou.
 *
 * <p>{@code syncService} peut être {@code null} si la synchro d'inventaire est désactivée
 * ({@code database.sync.enabled: false}) ; dans ce cas seul le verrou est géré.
 */
public class PlayerLockListener implements Listener {

    private static final Logger LOG = Logger.getLogger("PlayerLock");

    /** Intervalle entre deux tentatives d'acquisition pendant le pré-login. */
    private static final long POLL_MS = 150L;

    private final PlayerLockService lockService;
    private final PlayerDataSyncService syncService; // nullable
    private final String serverId;
    private final boolean kickOnConflict;
    private final long waitMillis;

    public PlayerLockListener(PlayerLockService lockService, PlayerDataSyncService syncService,
                              String serverId, boolean kickOnConflict, long waitMillis) {
        this.lockService = lockService;
        this.syncService = syncService;
        this.serverId = serverId;
        this.kickOnConflict = kickOnConflict;
        this.waitMillis = Math.max(0L, waitMillis);
    }

    /**
     * Prend le verrou avant que le joueur n'apparaisse, en laissant au serveur
     * précédent le temps de sauvegarder. Thread de connexion : l'attente ici ne
     * coûte rien au serveur.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        long deadline = System.currentTimeMillis() + waitMillis;

        while (!lockService.acquire(uuid, serverId)) {
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("[Lock] " + event.getName() + " est encore verrouillé sur un autre serveur "
                        + "après " + waitMillis + " ms (données potentiellement non sauvegardées).");
                if (kickOnConflict) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            "§cTransfert en cours...\n"
                            + "§7Tes données sont en cours de sauvegarde sur l'autre serveur.\n"
                            + "§7Reconnecte-toi dans quelques secondes.");
                } else {
                    // Le joueur entre quand même : le verrou doit le suivre, sinon
                    // sa sortie ne libérerait rien et sa prochaine arrivée ailleurs
                    // attendrait un serveur qui ne le détient plus vraiment.
                    lockService.takeOver(uuid, serverId);
                }
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Connexion refusée après coup (plein, banni, autre plugin) : le verrou pris ne sert plus. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            lockService.release(event.getPlayer().getUniqueId(), serverId);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
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
