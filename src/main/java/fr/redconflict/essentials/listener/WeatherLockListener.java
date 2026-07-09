package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.service.WeatherService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.WeatherChangeEvent;

/**
 * Verrou anti-pluie du serveur : annule le passage à la pluie, sauf dans les
 * mondes où un admin l'a forcée via /weather (voir {@link WeatherService}).
 * Remplace l'ancien {@code listeners.WeatherListener} qui bloquait tout.
 */
public class WeatherLockListener implements Listener {

    private final WeatherService weather;

    public WeatherLockListener(WeatherService weather) {
        this.weather = weather;
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState() && !weather.isOverridden(event.getWorld().getName())) {
            event.setCancelled(true);
        }
    }
}
