package fr.originsfight.essentials.service;

import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.WarpRepository;
import org.bukkit.Location;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Warps publics du serveur : validation des noms et accès à la persistance.
 */
public class WarpService {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]{1,16}");

    private final WarpRepository repository;

    public WarpService(WarpRepository repository) {
        this.repository = repository;
    }

    /** @return la position du warp, ou {@code null} (inexistant ou monde déchargé). */
    public Location find(String name) {
        StoredLocation stored = repository.find(normalize(name));
        return stored != null ? stored.toLocation() : null;
    }

    public List<String> names() {
        return repository.names();
    }

    /** @return false si le nom est invalide. */
    public boolean set(String name, Location location) {
        String normalized = normalize(name);
        if (!VALID_NAME.matcher(normalized).matches()) {
            return false;
        }
        repository.save(normalized, StoredLocation.of(location));
        return true;
    }

    public boolean delete(String name) {
        return repository.delete(normalize(name));
    }

    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
