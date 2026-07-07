package fr.originsfight.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

/**
 * Potion custom Fall Protection (effet ID 24, inconnue du NMS vanilla) :
 * les dégâts de chute sont interceptés via l'API Bukkit — niveau I = dégâts
 * réduits de moitié, niveau II et plus = immunité totale.
 */
public class FallProtectionListener implements Listener {

    private static final int FALL_PROTECTION_ID = 24;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();

        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().getId() != FALL_PROTECTION_ID) {
                continue;
            }
            if (effect.getAmplifier() >= 1) {
                event.setCancelled(true);
            } else {
                event.setDamage(event.getDamage() * 0.5);
            }
            return;
        }
    }
}
