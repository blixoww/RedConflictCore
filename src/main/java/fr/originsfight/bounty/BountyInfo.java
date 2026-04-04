package fr.originsfight.bounty;

import java.util.UUID;

/**
 * Représente une prime placée sur un joueur.
 */
public class BountyInfo {

    private final UUID setter;
    private final String setterName;
    private final UUID target;
    private final String targetName;
    private final long amount;
    private final long timestamp;

    /** Constructeur principal : timestamp = maintenant. */
    public BountyInfo(UUID setter, String setterName, UUID target, String targetName, long amount) {
        this(setter, setterName, target, targetName, amount, System.currentTimeMillis());
    }

    /** Constructeur de désérialisation (restauration depuis DB). */
    public BountyInfo(UUID setter, String setterName, UUID target, String targetName, long amount, long timestamp) {
        this.setter = setter;
        this.setterName = setterName;
        this.target = target;
        this.targetName = targetName;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public UUID getSetter()      { return setter; }
    public String getSetterName(){ return setterName; }
    public UUID getTarget()      { return target; }
    public String getTargetName(){ return targetName; }
    public long getAmount()      { return amount; }
    public long getTimestamp()   { return timestamp; }

    /** Temps restant en millisecondes (peut être négatif si expiré). */
    public long getRemainingMs(long durationMs) {
        return (timestamp + durationMs) - System.currentTimeMillis();
    }
}
