package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Cœur du système de primes automatiques (in-memory, sans base de données).
 *
 * Les primes sont déclenchées automatiquement par KillstreakManager quand
 * un seuil configurable est franchi. Elles sont remises à zéro au redémarrage.
 */
public class BountyManager {

    private static BountyManager instance;

    /** target UUID → BountyInfo */
    private final Map<UUID, BountyInfo> activeBounties = new HashMap<>();

    private final KillstreakManager ksManager;
    private FactionLeaveTracker factionTracker;

    // Config
    private final List<Long> thresholdAmounts = new ArrayList<>();
    private double escalationMultiplier = 1.5;
    private int    factionLeaveDays     = 7;

    public BountyManager(KillstreakManager ksManager) {
        this.ksManager = ksManager;
        instance = this;
    }

    public static BountyManager getInstance() { return instance; }

    // ── Init ──────────────────────────────────────────────────────────────────

    public boolean enable(JavaPlugin plugin) {
        escalationMultiplier = plugin.getConfig().getDouble("bounty.escalation-multiplier", 1.5);
        factionLeaveDays     = plugin.getConfig().getInt("bounty.faction-leave-days", 7);

        List<Map<?, ?>> tList = plugin.getConfig().getMapList("bounty.thresholds");
        for (Map<?, ?> entry : tList) {
            thresholdAmounts.add(((Number) entry.get("amount")).longValue());
        }

        factionTracker = new FactionLeaveTracker();

        ksManager.loadConfig(plugin);

        return true;
    }

    public void disable() {
        // Rien à persister
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean hasBounty(UUID target)    { return activeBounties.containsKey(target); }
    public BountyInfo getBounty(UUID target) { return activeBounties.get(target); }
    public Map<UUID, BountyInfo> getActiveBounties() { return Collections.unmodifiableMap(activeBounties); }
    public FactionLeaveTracker getFactionTracker()    { return factionTracker; }

    // ── Création / escalade ───────────────────────────────────────────────────

    public void onThresholdCrossed(Player player, int killstreak, int thresholdIndex) {
        UUID uuid = player.getUniqueId();

        if (activeBounties.containsKey(uuid)) {
            BountyInfo info = activeBounties.get(uuid);
            long oldAmount  = info.getAmount();
            long newAmount  = (long) Math.ceil(oldAmount * escalationMultiplier);
            info.setAmount(newAmount);
            info.setThresholdIndex(thresholdIndex);
            BountyAnnouncer.escalatedBounty(player.getName(), oldAmount, newAmount, killstreak);
        } else {
            if (thresholdIndex < 0 || thresholdIndex >= thresholdAmounts.size()) return;
            long amount = thresholdAmounts.get(thresholdIndex);
            BountyInfo info = new BountyInfo(uuid, player.getName(), amount, killstreak, thresholdIndex);
            activeBounties.put(uuid, info);
            BountyAnnouncer.newBounty(player.getName(), amount, killstreak);
        }
    }

    // ── Claim / résolution ────────────────────────────────────────────────────

    public void onBountyTargetKilled(Player victim, Player killer) {
        BountyInfo info = activeBounties.get(victim.getUniqueId());
        if (info == null) return;

        // Anti-bypass faction (session uniquement)
        String victimFactionId = FactionLeaveTracker.getFactionId(victim);
        if (victimFactionId != null
                && factionTracker.recentlyLeftFaction(killer.getUniqueId(), victimFactionId, factionLeaveDays)) {
            BountyAnnouncer.factionBypassDetected(killer.getName(), victim.getName());
            return;
        }

        // Kill légitime : verser la prime
        long amount = info.getAmount();
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        if (eco != null) eco.depositPlayer(killer, amount);

        int streakAtDeath = ksManager.getStreak(victim.getUniqueId());
        BountyAnnouncer.bountyClaimed(killer.getName(), victim.getName(), amount, streakAtDeath);
        killer.sendMessage("§a§l✦ §aPrime réclamée §8— §f§l+" + amount + "$ §acrédités sur votre compte !");

        activeBounties.remove(victim.getUniqueId());
    }

    public void onBountyTargetNonPvpDeath(Player victim) {
        // La prime reste active
    }
}
