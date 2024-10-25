package fr.originsfight.utils;

import java.util.HashMap;
import java.util.UUID;

public class CooldownManager {

    private static final HashMap<String, Long> cooldowns = new HashMap<>();

    // Méthode pour définir un cooldown avec UUID et clé
    public static void setCooldown(UUID uuid, String key, int seconds) {
        long time = System.currentTimeMillis() + (seconds * 1000L);
        cooldowns.put(uuid + "_" + key, time);
    }

    // Vérifie si un cooldown est actif pour un UUID et une clé
    public static boolean isOnCooldown(UUID uuid, String key) {
        String uniqueKey = uuid + "_" + key;
        Long time = cooldowns.get(uniqueKey);

        if (time == null) return false;
        if (System.currentTimeMillis() > time) {
            cooldowns.remove(uniqueKey);
            return false;
        }
        return true;
    }

    // Obtenir le temps restant d’un cooldown en secondes pour un UUID et une clé
    public static int getRemainingTime(UUID uuid, String key) {
        String uniqueKey = uuid + "_" + key;
        Long time = cooldowns.get(uniqueKey);

        if (time == null) return 0;
        long remainingTime = time - System.currentTimeMillis();
        return remainingTime > 0 ? (int) (remainingTime / 1000) : 0;
    }
}
