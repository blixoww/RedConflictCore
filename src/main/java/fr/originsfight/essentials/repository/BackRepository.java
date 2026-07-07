package fr.originsfight.essentials.repository;

import fr.originsfight.essentials.model.StoredLocation;

import java.util.UUID;

/**
 * Persistance de la dernière position /back de chaque joueur, scopée par serveur.
 * Permet à /back de survivre à un redémarrage.
 */
public interface BackRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** @return la dernière position enregistrée, ou {@code null}. */
    StoredLocation find(UUID player);

    /** Enregistre (ou remplace) la position /back du joueur. */
    void save(UUID player, StoredLocation location);
}
