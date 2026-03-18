package fr.originsfight.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Listener pour la potion custom Fall Protection (potion ID 24).
 * Côté serveur, le NMS compilé ne connaît pas notre potion custom,
 * donc on intercepte les dégâts de chute via Bukkit API.
 *
 * Fall Protection I (amplifier 0) : réduit de 50%
 * Fall Protection II (amplifier 1+) : immunité totale
 */
public class FallProtectionListener implements Listener {

    // Potion effect type ID 24 = Fall Protection (custom)
    private static final PotionEffectType FALL_PROTECTION = PotionEffectType.getById(24);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();

        if (FALL_PROTECTION == null) return;

        PotionEffect effect = null;
        for (PotionEffect pe : player.getActivePotionEffects()) {
            if (pe.getType().getId() == 24) {
                effect = pe;
                break;
            }
        }

        if (effect == null) return;

        int amplifier = effect.getAmplifier();
        if (amplifier >= 1) {
            // Fall Protection II+ : immunité totale
            event.setCancelled(true);
        } else {
            // Fall Protection I : réduit de 50%
            event.setDamage(event.getDamage() * 0.5);
        }
    }
}

