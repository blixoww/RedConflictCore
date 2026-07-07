package fr.originsfight.essentials.service;

import org.bukkit.entity.Player;

/**
 * Politique de blocage des demandes de téléportation. Implémentée au câblage
 * par /tpu (TP Unavailable) sans coupler le service à cette commande.
 */
public interface TeleportRequestPolicy {

    /** @return true si la demande de {@code requester} vers {@code target} doit être refusée. */
    boolean isBlocked(Player requester, Player target);
}
