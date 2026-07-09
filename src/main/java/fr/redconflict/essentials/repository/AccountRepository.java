package fr.redconflict.essentials.repository;

import java.util.UUID;

/**
 * Persistance des comptes économie (soldes), partagés entre tous les serveurs
 * du cluster via la base H2 centrale.
 */
public interface AccountRepository {

    /** Crée la table si nécessaire. @return false si la base est indisponible. */
    boolean init();

    boolean exists(UUID player);

    /** @return le solde, ou {@code null} si le compte n'existe pas. */
    Double findBalance(UUID player);

    /** Résolution hors ligne, insensible à la casse. @return null si inconnu. */
    UUID findUuidByName(String name);

    /** Dernier nom connu du compte. @return null si inconnu. */
    String findName(UUID player);

    /** Crée ou met à jour le compte (solde + dernier nom connu). */
    void save(UUID player, String name, double balance);
}
