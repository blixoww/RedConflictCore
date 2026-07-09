package fr.redconflict.cooldown;

import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Cooldowns gameplay en mémoire, partagés entre domaines (RTP, réparation,
 * combat-log). Singleton assumé : l'état de combat est consulté par plusieurs
 * modules (combatlog, server-switch, commandes désactivées en combat).
 *
 * <p>Les OP ne sont jamais en cooldown. L'état est perdu au redémarrage,
 * ce qui est voulu pour ces cooldowns courts.
 */
public final class CooldownManager {

    private static final CooldownManager INSTANCE = new CooldownManager();

    private final Map<UUID, EnumMap<CooldownType, Long>> expiries = new HashMap<>();

    private CooldownManager() {
    }

    public static CooldownManager instance() {
        return INSTANCE;
    }

    public void set(Player player, CooldownType type, long duration, TimeUnit unit) {
        expiries.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(CooldownType.class))
                .put(type, System.currentTimeMillis() + unit.toMillis(duration));
    }

    /** @return le temps restant en millisecondes (0 si expiré, ou si le joueur est OP). */
    public long timeLeft(Player player, CooldownType type) {
        if (player.isOp()) {
            return 0L;
        }
        EnumMap<CooldownType, Long> byType = expiries.get(player.getUniqueId());
        if (byType == null) {
            return 0L;
        }
        return Math.max(byType.getOrDefault(type, 0L) - System.currentTimeMillis(), 0L);
    }

    public boolean isOnCooldown(Player player, CooldownType type) {
        return timeLeft(player, type) > 0;
    }

    /** Efface tous les cooldowns du joueur (utilisé à la sortie du combat). */
    public void clear(Player player) {
        expiries.remove(player.getUniqueId());
    }
}
