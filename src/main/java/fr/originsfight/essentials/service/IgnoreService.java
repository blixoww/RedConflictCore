package fr.originsfight.essentials.service;

import fr.originsfight.essentials.repository.IgnoreRepository;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Joueurs ignorés (chat public et messages privés).
 *
 * <p>Le cache est chargé à la connexion et consulté depuis le thread du chat
 * asynchrone : structures concurrentes obligatoires, et {@link #isIgnoring}
 * ne touche jamais la base.
 */
public class IgnoreService {

    private final IgnoreRepository repository;
    private final Map<UUID, Set<UUID>> cache = new ConcurrentHashMap<>();

    public IgnoreService(IgnoreRepository repository) {
        this.repository = repository;
    }

    /** Charge la liste d'ignorés du joueur (à appeler à la connexion, thread principal). */
    public void load(UUID player) {
        cache.put(player, new CopyOnWriteArraySet<>(repository.findIgnored(player)));
    }

    /** Libère le cache à la déconnexion. */
    public void unload(UUID player) {
        cache.remove(player);
    }

    /** true si {@code viewer} ignore {@code subject}. Thread-safe, cache uniquement. */
    public boolean isIgnoring(UUID viewer, UUID subject) {
        Set<UUID> ignored = cache.get(viewer);
        return ignored != null && ignored.contains(subject);
    }

    /** Inverse l'état d'ignorance. @return le nouvel état (true = désormais ignoré). */
    public boolean toggle(UUID player, UUID target) {
        Set<UUID> ignored = cache.computeIfAbsent(player,
                k -> new CopyOnWriteArraySet<>(repository.findIgnored(k)));
        if (ignored.contains(target)) {
            ignored.remove(target);
            repository.remove(player, target);
            return false;
        }
        ignored.add(target);
        repository.add(player, target);
        return true;
    }
}
