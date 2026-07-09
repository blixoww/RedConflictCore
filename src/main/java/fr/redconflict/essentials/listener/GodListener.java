package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.service.PlayerStateService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

/**
 * Mode dieu (/god) : annule les dégâts de toutes sources et gèle la faim.
 * Priorité LOWEST pour que les autres plugins voient l'événement déjà annulé.
 */
public class GodListener implements Listener {

    private final PlayerStateService states;

    public GodListener(PlayerStateService states) {
        this.states = states;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player
                && states.isGod(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player
                && states.isGod(event.getEntity().getUniqueId())
                && event.getFoodLevel() < ((Player) event.getEntity()).getFoodLevel()) {
            event.setCancelled(true);
        }
    }
}
