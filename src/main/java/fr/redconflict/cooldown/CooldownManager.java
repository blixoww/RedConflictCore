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

    /** Efface tous les cooldowns du joueur (déconnexion définitive, purge). */
    public void clear(Player player) {
        expiries.remove(player.getUniqueId());
    }

    /**
     * Efface un seul cooldown.
     *
     * <p>Ce n'est pas un raffinement : {@link #clear(Player)} vide <b>tous</b> les
     * cooldowns. L'appeler à la mort pour lever le combat-log remettait aussi
     * {@code /rtp} et {@code /repairall} à zéro — mourir était donc le moyen le
     * plus rapide de recharger ses cooldowns, et sur un serveur PvP mourir ne
     * coûte rien quand on est déjà nu.
     */
    public void clear(Player player, CooldownType type) {
        EnumMap<CooldownType, Long> byType = expiries.get(player.getUniqueId());
        if (byType == null) {
            return;
        }
        byType.remove(type);
        if (byType.isEmpty()) {
            expiries.remove(player.getUniqueId());
        }
    }
}
