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

    private static final long TARGET_COOLDOWN_MS = 24L * 60 * 60 * 1000; // 24h

    private static BountyManager instance;

    /** target UUID → BountyInfo */
    private final Map<UUID, BountyInfo> activeBounties = new HashMap<>();

    /** placer UUID → target UUID (prime manuelle active) */
    private final Map<UUID, UUID> manualPlacer = new HashMap<>();

    /** target UUID → timestamp du dernier placement de prime (anti-harcèlement) */
    private final Map<UUID, Long> targetCooldown = new HashMap<>();

    private final KillstreakManager ksManager;
    private FactionLeaveTracker factionTracker;

    // Config
    private final List<Long> thresholdAmounts = new ArrayList<>();
    private double escalationMultiplier = 1.5;
    private int    factionLeaveDays     = 7;
    private long   minManualAmount      = 100L;

    public BountyManager(KillstreakManager ksManager) {
        this.ksManager = ksManager;
        instance = this;
    }

    public static BountyManager getInstance() { return instance; }

    // ── Init ─────────────────────────────────────────────────���────────────────

    public boolean enable(JavaPlugin plugin) {
        escalationMultiplier = plugin.getConfig().getDouble("bounty.escalation-multiplier", 1.5);
        factionLeaveDays     = plugin.getConfig().getInt("bounty.faction-leave-days", 7);
        minManualAmount      = plugin.getConfig().getLong("bounty.min-manual-amount", 100L);

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
    public long getMinManualAmount()                  { return minManualAmount; }

    /** Retourne true si une prime a déjà été posée sur cette cible dans les dernières 24h. */
    public boolean isTargetOnCooldown(UUID target) {
        Long last = targetCooldown.get(target);
        return last != null && (System.currentTimeMillis() - last) < TARGET_COOLDOWN_MS;
    }

    /** Temps restant en ms avant que la cible soit de nouveau primable. */
    public long targetCooldownRemaining(UUID target) {
        Long last = targetCooldown.get(target);
        if (last == null) return 0;
        long remaining = TARGET_COOLDOWN_MS - (System.currentTimeMillis() - last);
        return Math.max(0, remaining);
    }

    /** UUID de la cible d'une prime manuelle du placer, ou null. */
    public UUID getManualTarget(UUID placer) { return manualPlacer.get(placer); }

    // ── Prime manuelle ────────────────────────────────────────────────────────

    /**
     * Place une prime manuelle. Toutes les vérifications (fond, faction, ami, cooldown)
     * doivent avoir été faites AVANT d'appeler cette méthode.
     */
    public void placeManualBounty(UUID placerUuid, Player target, long amount, Economy eco) {
        // Débiter le poseur
        if (eco != null) eco.withdrawPlayer(org.bukkit.Bukkit.getOfflinePlayer(placerUuid), amount);

        // Créer ou augmenter la prime
        UUID tUuid = target.getUniqueId();
        if (activeBounties.containsKey(tUuid)) {
            activeBounties.get(tUuid).setAmount(activeBounties.get(tUuid).getAmount() + amount);
        } else {
            int ks = ksManager.getStreak(tUuid);
            activeBounties.put(tUuid, new BountyInfo(tUuid, target.getName(), amount, ks, -1));
        }

        manualPlacer.put(placerUuid, tUuid);
        targetCooldown.put(tUuid, System.currentTimeMillis());
    }

    /**
     * Annule la prime manuelle active du poseur et rembourse.
     * @return montant remboursé, ou -1 si pas de prime manuelle active.
     */
    public long cancelManualBounty(UUID placerUuid, Economy eco) {
        UUID target = manualPlacer.remove(placerUuid);
        if (target == null) return -1L;

        BountyInfo info = activeBounties.get(target);
        if (info == null) return -1L;

        long amount = info.getAmount();
        activeBounties.remove(target);
        if (eco != null) eco.depositPlayer(org.bukkit.Bukkit.getOfflinePlayer(placerUuid), amount);
        return amount;
    }

    // ── Création / escalade automatique ───────────────────────────────────────

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

        // Nettoyer le placer manuel si applicable
        manualPlacer.values().remove(victim.getUniqueId());
        activeBounties.remove(victim.getUniqueId());
    }

    public void onBountyTargetNonPvpDeath(Player victim) {
        // La prime reste active
    }
}
