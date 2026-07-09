package fr.redconflict.essentials.model;

import java.util.UUID;

/**
 * Demande de téléportation en attente (DTO), créée par /tpa ou /tpahere.
 * Une seule demande est conservée par cible : la plus récente écrase la précédente.
 */
public final class TeleportRequest {

    /** Sens de la téléportation une fois la demande acceptée. */
    public enum Type {
        /** /tpa : le demandeur se téléporte vers la cible. */
        TO_TARGET,
        /** /tpahere : la cible se téléporte vers le demandeur. */
        TO_REQUESTER
    }

    private final UUID requester;
    private final UUID target;
    private final Type type;
    private final long createdAt;

    public TeleportRequest(UUID requester, UUID target, Type type) {
        this.requester = requester;
        this.target = target;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getRequester() {
        return requester;
    }

    public UUID getTarget() {
        return target;
    }

    public Type getType() {
        return type;
    }

    public boolean isExpired(int expireSeconds) {
        return System.currentTimeMillis() - createdAt > expireSeconds * 1000L;
    }
}
