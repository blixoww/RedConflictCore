package fr.redconflict.essentials.model;

import java.util.UUID;

/**
 * Trace de passage d'un joueur (DTO) : première connexion, dernière connexion,
 * dernière déconnexion. Alimente /seen et la résolution nom → UUID hors ligne.
 */
public final class SeenRecord {

    private final UUID uuid;
    private final String name;
    private final long firstJoin;
    private final long lastJoin;
    private final long lastQuit;

    public SeenRecord(UUID uuid, String name, long firstJoin, long lastJoin, long lastQuit) {
        this.uuid = uuid;
        this.name = name;
        this.firstJoin = firstJoin;
        this.lastJoin = lastJoin;
        this.lastQuit = lastQuit;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public long getFirstJoin() {
        return firstJoin;
    }

    public long getLastJoin() {
        return lastJoin;
    }

    /** 0 si le joueur ne s'est jamais déconnecté proprement (ou est encore en ligne). */
    public long getLastQuit() {
        return lastQuit;
    }
}
