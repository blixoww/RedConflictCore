package fr.redconflict.lagswitch;

import org.bukkit.Location;

/**
 * État anti lag-switch d'un joueur.
 * Stocke les informations sur le lag-switch en cours.
 */
public class LagSwitchState {

    /** Nombre de ticks consécutifs avec un ping élevé. */
    public int consecutiveHighPingTicks = 0;

    /**
     * Timestamp (System.currentTimeMillis()) du début du lag-switch.
     * 0 = pas en lag.
     */
    public long laggingStartMs = 0;

    /**
     * Timestamp de fin de la grace-period.
     * 0 = grace-period inactive.
     */
    public long graceEndMs = 0;

    /**
     * Position où le joueur était quand le lag-switch a été déclenché.
     * Utilisé pour le rubber-band.
     */
    public Location freezeLocation = null;

    /** Retourne true si le joueur est actuellement en lag-switch. */
    public boolean isLagging() {
        return laggingStartMs > 0;
    }

    /** Retourne true si le joueur est dans la grace-period post-lag. */
    public boolean isInGrace() {
        return !isLagging() && graceEndMs > 0
                && System.currentTimeMillis() < graceEndMs;
    }
}

