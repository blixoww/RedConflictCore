package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.config.EssentialsConfig;
import fr.redconflict.essentials.service.BackService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Enregistre la position de mort pour /back (activable via
 * {@code back.save-on-death} dans essentials.yml).
 */
public class BackDeathListener implements Listener {

    private final BackService backService;
    private final EssentialsConfig config;

    public BackDeathListener(BackService backService, EssentialsConfig config) {
        this.backService = backService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (config.backOnDeath()) {
            backService.record(event.getEntity());
        }
    }
}
