package fr.originsfight.essentials.repository;

import fr.originsfight.essentials.model.StoredLocation;

import java.util.List;

/**
 * Persistance des warps publics, scopée par serveur.
 */
public interface WarpRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** @return le warp demandé, ou {@code null}. */
    StoredLocation find(String name);

    /** Noms des warps existants, ordonnés alphabétiquement. */
    List<String> names();

    /** Crée ou remplace un warp. */
    void save(String name, StoredLocation location);

    /** @return true si un warp a bien été supprimé. */
    boolean delete(String name);
}
