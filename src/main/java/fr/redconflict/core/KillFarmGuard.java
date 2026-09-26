package fr.redconflict.core;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Un kill rapporte-t-il quelque chose (succès, série, prime) ?
 *
 * <p><b>L'exploit.</b> Rien ne distinguait un vrai combat d'un second compte
 * qu'on tue en boucle : « 1000 kills » (200 000 $), « série de 25 »
 * (100 000 $) et les primes automatiques se farmaient seul, avec deux comptes.
 *
 * <p><b>Deux règles</b>, volontairement simples :
 * <ul>
 *   <li>tueur et victime sur la <b>même adresse IP</b> : rien ne compte ;</li>
 *   <li>la <b>même victime</b> ne compte qu'une fois toutes les
 *       {@link #REPEAT_WINDOW_MS} pour un même tueur.</li>
 * </ul>
 * La mort, elle, reste une mort : la série de la victime retombe toujours.
 *
 * <p>Plusieurs listeners lisent la même mort : le verdict est mémorisé par
 * événement, sinon le premier à demander « consommerait » le kill et le second
 * le verrait comme une répétition.
 */
public final class KillFarmGuard {

    public static final long REPEAT_WINDOW_MS = 10L * 60L * 1000L;

    private static final Map<String, Long> lastCounted = new HashMap<String, Long>();
    private static final Map<PlayerDeathEvent, Boolean> verdicts = new WeakHashMap<PlayerDeathEvent, Boolean>();

    private KillFarmGuard() { }

    /** Verdict pour cette mort : {@code true} si le kill doit rapporter. */
    public static synchronized boolean counts(PlayerDeathEvent event) {
        Boolean cached = verdicts.get(event);
        if (cached != null) return cached;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        boolean ok = killer != null && !killer.equals(victim)
                && !sameAddress(killer, victim)
                && !recentlyCounted(killer.getUniqueId(), victim.getUniqueId());
        verdicts.put(event, ok);
        return ok;
    }

    private static boolean recentlyCounted(UUID killer, UUID victim) {
        long now = System.currentTimeMillis();
        if (lastCounted.size() > 5000) purge(now);
        String key = killer + ">" + victim;
        Long last = lastCounted.get(key);
        if (last != null && now - last < REPEAT_WINDOW_MS) return true;
        lastCounted.put(key, now);
        return false;
    }

    private static void purge(long now) {
        for (Iterator<Long> it = lastCounted.values().iterator(); it.hasNext(); ) {
            if (now - it.next() >= REPEAT_WINDOW_MS) it.remove();
        }
    }

    /** Même IP publique — derrière Velocity, l'adresse transmise par le proxy. */
    public static boolean sameAddress(Player a, Player b) {
        String ia = host(a), ib = host(b);
        return ia != null && ia.equals(ib);
    }

    public static String host(Player p) {
        InetSocketAddress addr = p == null ? null : p.getAddress();
        return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
    }
}
