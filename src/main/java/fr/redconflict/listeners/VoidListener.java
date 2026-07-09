package fr.redconflict.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/** Renvoie au spawn les joueurs qui tombent dans le vide (aucun dégât). */
public class VoidListener implements Listener {

    @EventHandler
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID
                || !(event.getEntity() instanceof Player)) {
            return;
        }
        event.getEntity().teleport(event.getEntity().getWorld().getSpawnLocation());
        event.getEntity().setFallDistance(0.0F);
        event.setCancelled(true);
    }
}
