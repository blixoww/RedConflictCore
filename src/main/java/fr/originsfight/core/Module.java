package fr.originsfight.core;

/**
 * Unité fonctionnelle isolée du plugin : un domaine = un module.
 *
 * <p>Le cycle de vie est piloté par {@link ModuleManager} : activation dans l'ordre
 * d'enregistrement du bootstrap, désactivation en ordre inverse. Un module ne doit
 * dépendre que de ce qui lui est passé en constructeur (injection de dépendances),
 * jamais d'un autre module via singleton.
 */
public interface Module {

    /** Nom court affiché dans les logs (ex. "Bounty"). */
    String getName();

    /**
     * Active le module : câblage des commandes, listeners, services et schedulers.
     * Une exception levée ici est isolée par le {@link ModuleManager} : elle
     * n'empêche pas l'activation des modules suivants.
     */
    void enable() throws Exception;

    /** Libère les ressources (schedulers, sauvegardes, fermetures). Doit être idempotent. */
    default void disable() {
    }
}
