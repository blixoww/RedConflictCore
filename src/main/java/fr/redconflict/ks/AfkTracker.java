package fr.redconflict.ks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le temps passé AFK ne compte pas comme temps de jeu.
 *
 * <p><b>L'exploit.</b> Le temps de jeu était la durée de connexion brute : un
 * second compte laissé connecté une semaine validait « temps-100h » (100 000 $
 * et un bloc de cobalt), sans jouer.
 *
 * <p><b>Ce qui est une activité :</b> tourner la tête, parler, taper une
 * commande, casser, poser ou utiliser quelque chose. Le simple déplacement
 * n'en est PAS une : un joueur poussé par un courant d'eau ou posé dans un
 * wagonnet bouge sans rien faire — c'est la machine anti-AFK classique.
 *
 * <p>Au-delà de {@link #IDLE_AFTER_MS} sans activité, toute la période
 * d'inactivité (les 5 premières minutes comprises) est retirée du temps de jeu
 * via {@link KsListener#excludeIdle}.
 */
public final class AfkTracker implements Listener {

    static final long IDLE_AFTER_MS = 5L * 60L * 1000L;

    /** Dernière activité par joueur. Écrit aussi depuis le chat (asynchrone). */
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<UUID, Long>();
    /** Part de la période d'inactivité en cours déjà retirée du temps de jeu. */
    private final Map<UUID, Long> excluded = new ConcurrentHashMap<UUID, Long>();

    public void start(Plugin plugin) {
        for (Player p : Bukkit.getOnlinePlayers()) touch(p.getUniqueId());
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { tick(); }
        }, 20L * 60L, 20L * 60L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Long last = lastActivity.get(id);
            if (last == null) { touch(id); continue; }
            long idle = now - last;
            if (idle < IDLE_AFTER_MS) continue;
            Long done = excluded.get(id);
            long already = done == null ? 0L : done;
            KsListener.excludeIdle(id, idle - already);
            excluded.put(id, idle);
        }
    }

    public boolean isAfk(UUID id) {
        Long last = lastActivity.get(id);
        return last != null && System.currentTimeMillis() - last >= IDLE_AFTER_MS;
    }

    private void touch(UUID id) {
        lastActivity.put(id, System.currentTimeMillis());
        excluded.remove(id);
    }

    // ── Activités ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { touch(e.getPlayer().getUniqueId()); }

    /** LOWEST : doit passer avant KsListener, qui verse la session à la déconnexion. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        // Dernier relevé avant que KsListener ne verse la session.
        Long last = lastActivity.remove(id);
        Long done = excluded.remove(id);
        if (last != null) {
            long idle = System.currentTimeMillis() - last;
            if (idle >= IDLE_AFTER_MS) KsListener.excludeIdle(id, idle - (done == null ? 0L : done));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) touch(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent e) { touch(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) { touch(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { touch(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { touch(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { touch(e.getPlayer().getUniqueId()); }
}
