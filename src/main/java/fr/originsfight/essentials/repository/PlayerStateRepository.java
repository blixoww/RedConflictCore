package fr.originsfight.essentials.repository;

import fr.originsfight.essentials.model.PlayerFlags;

import java.util.UUID;

/**
 * Persistance des états joueur (god, fly) pour les restaurer à la connexion.
 * Données globales au cluster (cohérent avec la synchronisation d'inventaire).
 */
public interface PlayerStateRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** @return les états du joueur ({@link PlayerFlags#NONE} si inconnu). */
    PlayerFlags find(UUID player);

    void saveGod(UUID player, boolean god);

    void saveFly(UUID player, boolean fly);
}
