package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Gestionnaire des primes (bounties).
 *
 * Règles :
 *  - Un joueur ne peut placer qu'une seule prime à la fois.
 *  - Un joueur ne peut avoir qu'une seule prime sur sa tête à la fois.
 *  - On ne peut bounty que le dernier joueur qui vous a tué.
 *  - Durée : 24 heures réelles.
 *  - Persistance SQLite (survit aux redémarrages).
 *  - Remboursement différé si l'émetteur est hors-ligne à l'expiration.
 */
public class BountyManager {

    private static BountyManager instance;

    /** Durée de vie d'une prime en millisecondes (24 heures). */
    public static final long BOUNTY_DURATION_MS = 24L * 60L * 60L * 1000L;

    /** Montant minimum par défaut (configurable via config.yml). */
    private long minimumAmount = 100L;

    /** Clé = UUID cible → prime. */
    private final Map<UUID, BountyInfo> bounties = new HashMap<>();

    /** Clé = UUID émetteur → UUID cible (pour savoir si déjà une prime en cours). */
    private final Map<UUID, UUID> placedBy = new HashMap<>();

    /** Clé = UUID victime → UUID dernier tueur. */
    private final Map<UUID, UUID> lastKiller = new HashMap<>();

    private BountyDatabase database;
    private JavaPlugin plugin;

    public static BountyManager getInstance() { return instance; }

    public BountyManager() { instance = this; }

    // ── Initialisation ────────────────────────────────────────────────────────

    public boolean enable(JavaPlugin plugin) {
        this.plugin = plugin;
        // Lire le montant minimum depuis config
        if (plugin.getConfig().contains("bounty.minimum-amount")) {
            minimumAmount = plugin.getConfig().getLong("bounty.minimum-amount", 100L);
        }
        // Initialiser la base de données
        database = new BountyDatabase((OriginsFightCore) plugin);
        if (!database.init()) return false;
        // Charger les primes persistées
        for (BountyInfo info : database.loadAllBounties()) {
            bounties.put(info.getTarget(), info);
            placedBy.put(info.getSetter(), info.getTarget());
        }
        // Lancer le scheduler d'expiration
        startExpirationTask(plugin);
        return true;
    }

    public void disable() {
        if (database != null) database.close();
    }

    // ── Scheduler d'expiration ────────────────────────────────────────────────

    public void startExpirationTask(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkExpiredBounties, 20L * 60, 20L * 60);
    }

    private void checkExpiredBounties() {
        long now = System.currentTimeMillis();
        List<UUID> targets = new ArrayList<>(bounties.keySet());
        for (UUID targetUuid : targets) {
            BountyInfo info = bounties.get(targetUuid);
            if (info == null) continue;
            if (now - info.getTimestamp() >= BOUNTY_DURATION_MS) {
                expireBounty(info);
            }
        }
    }

    private void expireBounty(BountyInfo info) {
        // Retirer de la mémoire et de la DB
        bounties.remove(info.getTarget());
        placedBy.remove(info.getSetter());
        database.deleteBounty(info.getTarget());

        // Rembourser l'émetteur
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        Player setter = Bukkit.getPlayer(info.getSetter());
        if (setter != null && setter.isOnline()) {
            if (eco != null) eco.depositPlayer(setter, info.getAmount());
            setter.sendMessage(String.format(RC.PRE + "§eVotre prime sur §f%s §en'a pas été réclamée. §f+%d$ §eremboursé.",
                    info.getTargetName(), info.getAmount()));
        } else {
            // Stocker le remboursement pour plus tard
            database.addRefund(info.getSetter(), info.getAmount());
        }

        // Annonce globale
        for (String line : RC.fmt(RC.BOUNTY_EXPIRED_BROADCAST, info.getTargetName(), info.getAmount()).split("\n")) {
            Bukkit.broadcastMessage(line);
        }
    }

    // ── Gestion des primes ────────────────────────────────────────────────────

    public boolean hasPlacedBounty(UUID setter) { return placedBy.containsKey(setter); }
    public boolean hasBounty(UUID target)        { return bounties.containsKey(target); }
    public BountyInfo getBounty(UUID target)     { return bounties.get(target); }
    public long getMinimumAmount()               { return minimumAmount; }

    /** Place une prime et la persiste en base. */
    public void placeBounty(UUID setter, String setterName, UUID target, String targetName, long amount) {
        BountyInfo info = new BountyInfo(setter, setterName, target, targetName, amount);
        bounties.put(target, info);
        placedBy.put(setter, target);
        database.insertBounty(info);
    }

    /** Retire la prime d'une cible (kill réussi) et libère le commanditaire. Retourne l'info retirée. */
    public BountyInfo removeBounty(UUID target) {
        BountyInfo info = bounties.remove(target);
        if (info != null) {
            placedBy.remove(info.getSetter());
            database.deleteBounty(target);
        }
        return info;
    }

    public Map<UUID, BountyInfo> getBounties() { return Collections.unmodifiableMap(bounties); }

    // ── Dernier tueur ─────────────────────────────────────────────────────────

    /** Enregistre que {@code killerUuid} vient de tuer {@code victimUuid}. */
    public void recordKill(UUID victimUuid, UUID killerUuid) {
        lastKiller.put(victimUuid, killerUuid);
    }

    /** Retourne le dernier tueur d'un joueur (null si aucun). */
    public UUID getLastKiller(UUID victimUuid) { return lastKiller.get(victimUuid); }

    // ── Remboursements différés ───────────────────────────────────────────────

    /** À appeler à la connexion d'un joueur pour lui verser un éventuel remboursement. */
    public void creditPendingRefund(Player player) {
        if (database == null) return;
        long amount = database.popRefund(player.getUniqueId());
        if (amount > 0) {
            Economy eco = OriginsFightCore.getInstance().getEconomy();
            if (eco != null) eco.depositPlayer(player, amount);
            player.sendMessage(String.format(RC.PRE + "§eRemboursement de prime perçu : §f+%d$§e.", amount));
        }
    }
}
