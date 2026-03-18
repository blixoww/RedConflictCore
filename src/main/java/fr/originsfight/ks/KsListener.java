package fr.originsfight.ks;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listener KS : écoute les connexions, déconnexions, kills et morts PvP.
 */
public class KsListener implements Listener {

    private final KsDatabase db;

    // Timestamp de connexion par joueur (pour calculer le temps de jeu)
    private static final Map<UUID, Long> joinTimes = new HashMap<>();

    public KsListener(KsDatabase db) { this.db = db; }

    /** Retourne le timestamp de connexion d'un joueur (pour la session en cours). */
    public static Long getJoinTime(UUID uuid) { return joinTimes.get(uuid); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        db.ensurePlayer(p);
        long now = System.currentTimeMillis();
        joinTimes.put(p.getUniqueId(), now);
        db.setJoinTime(p.getUniqueId(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Long joinTime = joinTimes.remove(p.getUniqueId());
        if (joinTime != null) {
            long seconds = (System.currentTimeMillis() - joinTime) / 1000;
            db.addPlaytime(p.getUniqueId(), seconds);
        }
    }

    /**
     * Écoute les morts PvP.
     * Incrémente les kills du tueur et les deaths de la victime.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller(); // null si mort non-pvp

        // Mort de la victime (causée par un joueur)
        if (killer != null && !killer.equals(victim)) {
            db.addDeath(victim.getUniqueId());
            db.addKill(killer.getUniqueId());
        }
    }
}

