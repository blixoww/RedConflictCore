package fr.redconflict.essentials.service;

import fr.redconflict.essentials.model.StoredLocation;
import fr.redconflict.essentials.repository.BackRepository;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dernière position de chaque joueur pour /back : alimentée avant chaque
 * téléportation du module et à la mort. Cache mémoire + persistance H2
 * (la position survit ainsi à un redémarrage du serveur).
 */
public class BackService {

    private final BackRepository repository;
    private final Map<UUID, Location> lastPositions = new HashMap<>();

    public BackService(BackRepository repository) {
        this.repository = repository;
    }

    /** Enregistre la position actuelle du joueur comme cible de son prochain /back. */
    public void record(Player player) {
        Location location = player.getLocation().clone();
        lastPositions.put(player.getUniqueId(), location);
        repository.save(player.getUniqueId(), StoredLocation.of(location));
    }

    /** @return la position /back, ou {@code null} si aucune (ou monde déchargé). */
    public Location find(UUID player) {
        Location cached = lastPositions.get(player);
        if (cached != null) return cached;
        StoredLocation stored = repository.find(player);
        return stored != null ? stored.toLocation() : null;
    }

    /** Libère le cache mémoire à la déconnexion (la base conserve la position). */
    public void forget(UUID player) {
        lastPositions.remove(player);
    }
}
