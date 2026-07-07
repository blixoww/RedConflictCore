package fr.originsfight.essentials.listener;

import fr.originsfight.essentials.economy.CoreEconomyProvider;
import fr.originsfight.essentials.service.IgnoreService;
import fr.originsfight.essentials.service.InvseeSessions;
import fr.originsfight.essentials.service.PlayerStateService;
import fr.originsfight.essentials.service.SeenService;
import fr.originsfight.essentials.service.TeleportRequestService;
import fr.originsfight.essentials.service.TeleportService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cycle de vie des joueurs pour le module essentials : chargement des caches
 * à la connexion (ignore, god/fly, solde), traces /seen, et purge de tout
 * l'état volatile à la déconnexion (téléportations en attente, sessions...).
 */
public class ConnectionListener implements Listener {

    private final SeenService seen;
    private final IgnoreService ignores;
    private final PlayerStateService states;
    private final TeleportService teleports;
    private final TeleportRequestService requests;
    private final InvseeSessions invsee;
    /** Null quand le provider économie interne est désactivé (essentials.yml). */
    private final CoreEconomyProvider economyProvider;

    public ConnectionListener(SeenService seen, IgnoreService ignores, PlayerStateService states,
                              TeleportService teleports, TeleportRequestService requests,
                              InvseeSessions invsee, CoreEconomyProvider economyProvider) {
        this.seen = seen;
        this.ignores = ignores;
        this.states = states;
        this.teleports = teleports;
        this.requests = requests;
        this.invsee = invsee;
        this.economyProvider = economyProvider;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        seen.recordJoin(player);
        ignores.load(player.getUniqueId());
        states.handleJoin(player);
        if (economyProvider != null) {
            economyProvider.handleJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        seen.recordQuit(player);
        states.handleQuit(player);
        ignores.unload(player.getUniqueId());
        teleports.clear(player.getUniqueId());
        requests.clear(player.getUniqueId());
        invsee.close(player.getUniqueId());
        if (economyProvider != null) {
            economyProvider.handleQuit(player);
        }
    }
}
