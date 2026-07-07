package fr.originsfight.essentials.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooldowns par joueur et par clé de commande, en mémoire uniquement
 * (aucune persistance nécessaire : un redémarrage remet les compteurs à zéro).
 */
public class CooldownService {

    /** uuid joueur → (clé de commande → timestamp d'expiration epoch ms). */
    private final Map<UUID, Map<String, Long>> expiries = new ConcurrentHashMap<>();

    /** @return millisecondes restantes, 0 si aucune attente. */
    public long remaining(UUID player, String key) {
        Map<String, Long> byKey = expiries.get(player);
        if (byKey == null) return 0L;
        Long expiry = byKey.get(key);
        if (expiry == null) return 0L;
        return Math.max(0L, expiry - System.currentTimeMillis());
    }

    /** Arme le cooldown ({@code seconds <= 0} : sans effet). */
    public void arm(UUID player, String key, int seconds) {
        if (seconds <= 0) return;
        expiries.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(key, System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Purge les entrées expirées (appelé périodiquement par le module).
     * Les cooldowns actifs sont volontairement conservés à la déconnexion :
     * un relog ne doit pas permettre de les contourner.
     */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Long>> entry : expiries.entrySet()) {
            entry.getValue().values().removeIf(expiry -> expiry <= now);
            if (entry.getValue().isEmpty()) {
                expiries.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}