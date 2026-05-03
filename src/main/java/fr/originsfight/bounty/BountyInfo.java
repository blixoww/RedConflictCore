package fr.originsfight.bounty;

import java.util.UUID;

/** Prime automatique posée par le serveur sur un joueur en killstreak élevé. */
public class BountyInfo {

    private final UUID   target;
    private final String targetName;
    private long  amount;
    private final int    killstreakAtCreation;
    private int   thresholdIndex;
    private final long   createdAt;

    public BountyInfo(UUID target, String targetName, long amount, int killstreak, int thresholdIndex) {
        this(target, targetName, amount, killstreak, thresholdIndex, System.currentTimeMillis());
    }

    public BountyInfo(UUID target, String targetName, long amount, int killstreak, int thresholdIndex, long createdAt) {
        this.target              = target;
        this.targetName          = targetName;
        this.amount              = amount;
        this.killstreakAtCreation = killstreak;
        this.thresholdIndex      = thresholdIndex;
        this.createdAt           = createdAt;
    }

    public UUID   getTarget()              { return target; }
    public String getTargetName()          { return targetName; }
    public long   getAmount()              { return amount; }
    public int    getKillstreakAtCreation(){ return killstreakAtCreation; }
    public int    getThresholdIndex()      { return thresholdIndex; }
    public long   getCreatedAt()           { return createdAt; }

    public void setAmount(long amount)             { this.amount = amount; }
    public void setThresholdIndex(int idx)         { this.thresholdIndex = idx; }
}
