package fr.originsfight.essentials.repository;

import fr.originsfight.essentials.model.SeenRecord;

import java.util.UUID;

/**
 * Persistance des traces de connexion (/seen) et référentiel nom → UUID
 * pour les commandes acceptant des joueurs hors ligne (/pay, /eco...).
 * Données globales au cluster (non scopées par serveur).
 */
public interface SeenRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    /** @return la trace du joueur, ou {@code null}. */
    SeenRecord find(UUID player);

    /** Recherche insensible à la casse sur le dernier nom connu. @return null si inconnu. */
    SeenRecord findByName(String name);

    /** Enregistre une connexion (initialise first_join au premier passage). */
    void recordJoin(UUID player, String name, long timestamp);

    /** Enregistre une déconnexion. */
    void recordQuit(UUID player, long timestamp);
}
