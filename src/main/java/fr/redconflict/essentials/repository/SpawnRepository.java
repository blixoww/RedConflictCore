package fr.redconflict.essentials.repository;

import fr.redconflict.essentials.model.StoredLocation;

/**
 * Persistance du point de spawn du serveur courant (un par server-id).
 */
public interface SpawnRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** @return le spawn défini pour ce serveur, ou {@code null}. */
    StoredLocation find();

    /** Définit ou remplace le spawn de ce serveur. */
    void save(StoredLocation location);
}
