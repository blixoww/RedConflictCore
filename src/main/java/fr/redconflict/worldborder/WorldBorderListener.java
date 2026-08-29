package fr.redconflict.worldborder;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Applique la bordure aux mondes chargés après le démarrage du module — un monde
 * créé par un plugin tiers, ou chargé à la demande, doit être borné lui aussi.
 */
public class WorldBorderListener implements Listener {

    private final WorldBorderService service;

    public WorldBorderListener(WorldBorderService service) {
        this.service = service;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        service.apply(event.getWorld());
    }
}
