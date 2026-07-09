package fr.redconflict.essentials.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordination entre /weather et le verrou anti-pluie du serveur : le listener
 * qui annule la pluie laisse passer les mondes où un admin a forcé la météo.
 */
public class WeatherService {

    /** Mondes où la pluie/l'orage a été forcé par un admin. */
    private final Set<String> overriddenWorlds = ConcurrentHashMap.newKeySet();

    public boolean isOverridden(String worldName) {
        return overriddenWorlds.contains(worldName);
    }

    /** Autorise la pluie forcée dans ce monde (jusqu'à retour au beau temps). */
    public void allowRain(String worldName) {
        overriddenWorlds.add(worldName);
    }

    /** Retire l'exception (retour au verrou anti-pluie normal). */
    public void clearOverride(String worldName) {
        overriddenWorlds.remove(worldName);
    }
}
