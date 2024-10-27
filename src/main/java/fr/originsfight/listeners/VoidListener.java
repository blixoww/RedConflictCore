package fr.originsfight.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public class VoidListener implements Listener {
    @EventHandler
    public void onPlayerFallToVoid(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            Entity entity = event.getEntity();
            if (entity == null)
                return;
            if (!(entity instanceof Player))
                return;
            entity.teleport(entity.getWorld().getSpawnLocation());
            entity.setFallDistance(0.0F);
            event.setCancelled(true);
        }
    }
}
