package fr.originsfight.essentials.repository;

import fr.originsfight.essentials.model.StoredLocation;

import java.util.Map;
import java.util.UUID;

/**
 * Persistance des homes, scopée par serveur (les mondes du Faction et du Minage
 * sont distincts alors que la base H2 est partagée).
 */
public interface HomeRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** Homes du joueur, indexés par nom (minuscule), ordonnés alphabétiquement. */
    Map<String, StoredLocation> findAll(UUID player);

    /** @return le home demandé, ou {@code null}. */
    StoredLocation find(UUID player, String name);

    boolean exists(UUID player, String name);

    int count(UUID player);

    /** Crée ou remplace un home. */
    void save(UUID player, String name, StoredLocation location);

    /** @return true si un home a bien été supprimé. */
    boolean delete(UUID player, String name);
}
