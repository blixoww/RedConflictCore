package fr.redconflict.essentials.repository;

import java.util.Set;
import java.util.UUID;

/**
 * Persistance des paires "joueur → joueurs ignorés" (chat et messages privés).
 * Données globales au cluster.
 */
public interface IgnoreRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** UUID des joueurs ignorés par {@code player}. */
    Set<UUID> findIgnored(UUID player);

    void add(UUID player, UUID ignored);

    void remove(UUID player, UUID ignored);
}
