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

    public BountyInfo(UUID setter, String setterName, UUID target, String targetName, long amount) {
        this.setter = setter;
        this.setterName = setterName;
        this.target = target;
        this.targetName = targetName;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getSetter() { return setter; }
    public String getSetterName() { return setterName; }
    public UUID getTarget() { return target; }
    public String getTargetName() { return targetName; }
    public long getAmount() { return amount; }
    public long getTimestamp() { return timestamp; }
}
