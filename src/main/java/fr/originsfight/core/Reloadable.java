package fr.originsfight.core;

/**
 * Marque un {@link Module} dont la configuration peut être rechargée à chaud
 * via {@code /red reload} sans redémarrage du serveur.
 */
public interface Reloadable {

    /** Recharge la configuration du module depuis le disque. */
    void reload();
}
