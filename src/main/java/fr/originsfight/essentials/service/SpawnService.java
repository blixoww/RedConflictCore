package fr.originsfight.essentials.service;

import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.SpawnRepository;
import org.bukkit.Location;

/**
 * Point de spawn du serveur : cache mémoire + persistance H2.
 * Si aucun spawn n'a été défini via /setspawn, retombe sur le spawn du monde.
 */
public class SpawnService {

    private final SpawnRepository repository;
    private StoredLocation cached;

    public SpawnService(SpawnRepository repository) {
        this.repository = repository;
        this.cached = repository.find();
    }

    /** @return le spawn défini, ou {@code null} si aucun (ou monde déchargé). */
    public Location find() {
        return cached != null ? cached.toLocation() : null;
    }

    public boolean isDefined() {
        return cached != null;
    }

    /** Définit le spawn du serveur et aligne le spawn du monde (boussoles, premiers joins). */
    public void set(Location location) {
        this.cached = StoredLocation.of(location);
        repository.save(this.cached);
        location.getWorld().setSpawnLocation(
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
