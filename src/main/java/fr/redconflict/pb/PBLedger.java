package fr.redconflict.pb;

import java.util.UUID;

/**
 * Stockage du solde en Points Boutique.
 *
 * <p>Deux implémentations, choisies en configuration :
 * {@link H2PBLedger} (historique, base de jeu) et {@link SitePBLedger}
 * (base du site, partagée avec la boutique web).
 *
 * <p>L'interface n'existe que pour rendre la bascule réversible : si le pont
 * vers le site pose problème en production, {@code pb.ledger: h2} ramène le
 * comportement d'avant sans toucher au code. Elle n'a pas vocation à accueillir
 * un troisième backend.
 *
 * <p><b>Contrat.</b> {@link #remove} doit être atomique : lire le solde, le
 * comparer et le décrémenter sans qu'un autre débit puisse s'intercaler. C'est
 * tout l'intérêt d'avoir un seul emplacement de stockage — deux copies
 * répliquées ne peuvent pas honorer ce contrat.
 */
public interface PBLedger {

    /** Nom court pour les journaux. */
    String getName();

    /** {@code false} si le stockage est momentanément injoignable. */
    boolean isAvailable();

    /** Crée la ligne du joueur si elle manque. Met à jour le pseudo connu. */
    void ensure(UUID uuid, String name);

    /** Solde courant. 0 si le joueur est inconnu ou le stockage indisponible. */
    int get(UUID uuid);

    /** Crédite. {@code false} si l'écriture a échoué. */
    boolean add(UUID uuid, int amount);

    /** Débite, et seulement si le solde le permet. Atomique. */
    boolean remove(UUID uuid, int amount);

    /** Fixe le solde. Négatif ramené à 0. */
    void set(UUID uuid, int amount);
}
