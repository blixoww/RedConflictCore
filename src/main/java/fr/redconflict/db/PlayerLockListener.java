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
 * <p><b>L'ordre réel d'un transfert Velocity, et ce qu'il impose.</b> Le proxy
 * ne ferme PAS la connexion à l'ancien serveur dès que la nouvelle est ouverte :
 * il termine d'abord toute la séquence de connexion sur le serveur d'arrivée, et
 * ne coupe l'ancienne qu'une fois reçu son {@code JoinGame}. Le
 * {@code PlayerQuitEvent} du serveur de départ arrive donc APRÈS le
 * {@code PlayerJoinEvent} du serveur d'arrivée — et attendre la libération du
 * verrou pendant le pré-login de l'arrivée crée un blocage circulaire : le
 * départ ne peut pas libérer tant que l'arrivée n'a pas fini, et l'arrivée
 * attend le départ. Seule l'expiration du délai le dénoue, après quoi les
 * données chargées sont périmées de toute façon.
 *
 * <p>La solution ne peut donc pas vivre ici : c'est le serveur de DÉPART qui
 * doit sauvegarder et relâcher, avant même de demander le transfert. Voir
 * {@link HandoffService}, appelé par {@code ServerSwitchCommand}.
 *
 * <p>Ce qui reste ici est un filet pour les arrivées qui ne suivent pas ce
 * chemin (reconnexion directe, retour après un crash) : une attente COURTE, qui
 * ne coûte rien quand le verrou est déjà libre — le cas normal désormais.
 */
public class PlayerLockListener implements Listener {

    private static final Logger LOG = Logger.getLogger("PlayerLock");

    /** Intervalle entre deux tentatives d'acquisition pendant le pré-login. */
    private static final long POLL_MS = 100L;

    private final PlayerLockService lockService;
    private final PlayerDataSyncService syncService; // nullable
    private final HandoffService handoff;
    private final String serverId;
    private final boolean kickOnConflict;
    private final long waitMillis;

    public PlayerLockListener(PlayerLockService lockService, PlayerDataSyncService syncService,
                              HandoffService handoff, String serverId, boolean kickOnConflict,
                              long waitMillis) {
        this.lockService = lockService;
        this.syncService = syncService;
        this.handoff = handoff;
        this.serverId = serverId;
        this.kickOnConflict = kickOnConflict;
        this.waitMillis = Math.max(0L, waitMillis);
    }

    /**
     * Prend le verrou avant que le joueur n'apparaisse.
     *
     * <p>L'attente doit rester COURTE : pendant un transfert Velocity, le
     * serveur de départ est incapable de libérer tant que nous n'avons pas fini
     * (voir l'en-tête de classe). Une longue attente n'y changerait rien et
     * retarderait chaque changement de serveur d'autant.
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
                        + "après " + waitMillis + " ms. Si c'est un transfert /hub /minage /faction, "
                        + "le serveur de départ n'a pas fait sa remise de relais (jar à jour ?).");
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
        // Un joueur qui revient ici ne doit pas traîner la marque d'un transfert
        // précédent : sinon sa prochaine sortie ne sauvegarderait rien.
        if (handoff != null) {
            handoff.clear(p.getUniqueId());
        }
        if (syncService != null) {
            try {
                syncService.loadAndApply(p);
            } catch (Exception e) {
                LOG.severe("[Sync] Chargement inventaire " + p.getName() + " échoué : " + e.getMessage());
            }
        }
    }

    /**
     * Sauvegarde puis libération — sauf si la remise de relais a déjà eu lieu.
     *
     * <p>Dans ce cas le joueur est déjà en train de jouer ailleurs : resauvegarder
     * l'instantané qu'on garde de lui écraserait dans H2 ce qu'il vient d'y faire.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (handoff != null && handoff.consume(p.getUniqueId())) {
            return; // déjà sauvegardé et relâché avant le transfert
        }
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
