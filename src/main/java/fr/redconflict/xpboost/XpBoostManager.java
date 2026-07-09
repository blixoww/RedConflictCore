package fr.redconflict.xpboost;

import fr.redconflict.data.PlayerDatabase;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère l'état du boost d'XP x2 (item {@code xp_booster}).
 *
 * Le boost double l'XP des métiers pendant {@link #BOOST_DURATION_MS}. L'état
 * (timestamp de fin) est persisté dans {@link PlayerDatabase} pour survivre aux
 * reconnexions et redémarrages, et mis en cache mémoire car l'XP des métiers est
 * lue très fréquemment (chaque bloc cassé / craft).
 */
public class XpBoostManager {

    /** ID NMS de l'item xp_booster (doit correspondre au client + Spigot-Server). */
    public static final int ITEM_ID = 490;

    /** Durée d'un boost : 2 heures. */
    public static final long BOOST_DURATION_MS = 2L * 60L * 60L * 1000L;

    private final PlayerDatabase database;
    private final ConcurrentHashMap<UUID, Long> cache = new ConcurrentHashMap<>();

    public XpBoostManager(PlayerDatabase database) {
        this.database = database;
    }

    /** Timestamp (ms) de fin du boost, depuis le cache ou la base (chargé à la volée). */
    private long until(UUID uuid) {
        Long cached = cache.get(uuid);
        if (cached != null) return cached;
        long v = database.getXpBoostUntil(uuid);
        cache.put(uuid, v);
        return v;
    }

    /** True si le joueur a un boost x2 actif. */
    public boolean isActive(UUID uuid) {
        return System.currentTimeMillis() < until(uuid);
    }

    /** Temps restant (ms) avant la fin du boost, 0 si inactif. */
    public long getRemainingMs(UUID uuid) {
        return Math.max(0L, until(uuid) - System.currentTimeMillis());
    }

    /**
     * Active (ou prolonge) le boost de {@link #BOOST_DURATION_MS}. Si un boost est
     * déjà actif, la durée s'ajoute au temps restant (le joueur ne perd pas l'item).
     *
     * @return le temps total restant (ms) après activation.
     */
    public long activate(Player player) {
        UUID uuid = player.getUniqueId();
        database.ensurePlayer(player);
        long now = System.currentTimeMillis();
        long base = Math.max(now, until(uuid));
        long newUntil = base + BOOST_DURATION_MS;
        cache.put(uuid, newUntil);
        database.setXpBoostUntil(uuid, newUntil);
        return newUntil - now;
    }

    /** Libère le cache d'un joueur (à la déconnexion). */
    public void unload(UUID uuid) {
        cache.remove(uuid);
    }
}
