package fr.redconflict.job;

import fr.redconflict.RedConflictCore;
import fr.redconflict.job.JobPacketSender;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gestionnaire principal du système de métiers.
 * Gère le cache mémoire, l'XP, les passages de niveau, les récompenses.
 */
public class JobManager {

    private static final Logger LOG = Logger.getLogger("Jobs");
    private static JobManager instance;

    private final RedConflictCore plugin;
    private final JobDatabase     database;
    private final JobConfig       config;
    private JobPacketSender        packetSender;
    private JobTopManager          topManager;
    private Economy                economy;
    private fr.redconflict.xpboost.XpBoostManager xpBoostManager;

    /** Cache mémoire UUID → JobData (évite les lectures SQLite en jeu). */
    private final Map<UUID, JobDatabase.JobData> cache = new ConcurrentHashMap<>();

    public static JobManager getInstance() { return instance; }

    public JobManager(RedConflictCore plugin, JobDatabase database, JobConfig config) {
        this.plugin   = plugin;
        this.database = database;
        this.config   = config;
        instance      = this;
        setupEconomy();
    }

    public void setPacketSender(JobPacketSender sender) { this.packetSender = sender; }
    public JobPacketSender getPacketSender() { return packetSender; }
    public void setTopManager(JobTopManager topManager) { this.topManager = topManager; }
    public JobTopManager getTopManager() { return topManager; }
    public void setXpBoostManager(fr.redconflict.xpboost.XpBoostManager m) { this.xpBoostManager = m; }

    /** Renvoie au client un JOB_DATA frais (ex. après (dés)activation du boost x2). */
    public void resendJobData(Player p) {
        if (p != null && packetSender != null) packetSender.sendJobData(p, getData(p.getUniqueId()));
    }
    public JobDatabase  getDatabase() { return database; }
    public JobConfig    getConfig()   { return config; }

    // ── Chargement / Déchargement ──────────────────────────────────────────────

    /** Charge les données d'un joueur depuis la DB en cache (appelé à la connexion). */
    public JobDatabase.JobData load(UUID uuid) {
        JobDatabase.JobData d = database.loadPlayer(uuid);
        cache.put(uuid, d);
        return d;
    }

    /** Sauvegarde les données d'un joueur et retire du cache. */
    public void unload(UUID uuid) {
        JobDatabase.JobData d = cache.remove(uuid);
        if (d != null) database.savePlayer(uuid, d);
    }

    /** Sauvegarde toutes les données en cache (appelé onDisable). */
    public void saveAll() {
        for (Map.Entry<UUID, JobDatabase.JobData> e : cache.entrySet()) {
            database.savePlayer(e.getKey(), e.getValue());
        }
    }

    // ── API publique ───────────────────────────────────────────────────────────

    public JobDatabase.JobData getData(UUID uuid) {
        return cache.computeIfAbsent(uuid, database::loadPlayer);
    }

    /** Niveau actuel d'un joueur pour un métier (sert à choisir le palier d'XP). */
    public int getLevel(Player player, JobType job) {
        return getData(player.getUniqueId()).getLevelFor(job);
    }

    /** @deprecated Tous les métiers sont actifs — conservé pour compatibilité admin */
    public JobType getActiveJob(UUID uuid) { return JobType.NONE; }

    // ── API publique ───────────────────────────────────────────────────────────

    /**
     * Donne de l'XP pour le métier donné.
     * Tous les métiers étant toujours actifs, l'XP est toujours accordé.
     */
    public boolean giveXp(Player player, JobType job, int amount) {
        if (amount <= 0 || !job.isReal()) return false;
        UUID uuid = player.getUniqueId();

        // Boost d'XP x2 (item xp_booster) : double l'XP métiers tant qu'il est actif.
        if (xpBoostManager != null && xpBoostManager.isActive(uuid)) {
            amount *= 2;
        }

        JobDatabase.JobData d = getData(uuid);

        int level  = d.getLevelFor(job);
        int curXp  = d.getXpFor(job);
        int maxLvl = config.getMaxLevels();
        if (level >= maxLvl) return false;

        curXp += amount;

        int levelsGained = 0;
        List<Integer> newLevels = new ArrayList<>();
        while (level < maxLvl) {
            int needed = config.getXpRequired(level + 1);
            if (curXp < needed) break;
            curXp -= needed;
            level++;
            levelsGained++;
            newLevels.add(level);
        }

        d.setLevel(job, level);
        d.setXp(job, curXp);
        database.addXp(uuid, job, amount, level, curXp);

        int xpForNext = level < maxLvl ? config.getXpRequired(level + 1) : 0;
        if (packetSender != null) {
            packetSender.sendXpGain(player, job, amount, curXp, level, xpForNext);
        }

        for (int lvl : newLevels) {
            processLevelUp(player, job, lvl);
        }

        return true;
    }

    // ── Passage de niveau ──────────────────────────────────────────────────────

    private void processLevelUp(Player player, JobType job, int newLevel) {
        long money        = config.getMoneyReward(job, newLevel);
        List<ItemStack> items = config.getItemRewards(job, newLevel);

        // Donner l'argent via Vault
        if (money > 0 && economy != null) {
            economy.depositPlayer(player, money);
        }

        // Donner les items
        for (ItemStack is : items) {
            if (is == null || is.getType() == null) continue;
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(is.clone());
            leftover.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        player.updateInventory();

        // Title serveur (Spigot ≥ 1.8)
        String title    = "§6✦ §e" + job.color + job.displayName;
        String subtitle = "§7Niveau §f§l" + newLevel + " §7atteint !";
        try {
            player.sendTitle(title, subtitle);
        } catch (Exception ignored) {}

        // Packet level up → animation client
        if (packetSender != null) {
            packetSender.sendLevelUp(player, job, newLevel, money, items, config.getRewardString(job, newLevel));
        }

        LOG.info("[Jobs] " + player.getName() + " → " + job.displayName + " niveau " + newLevel);
    }

    // ── Admin helpers ──────────────────────────────────────────────────────────

    /** Force le niveau et XP d'un joueur pour un métier. */
    public void forceLevel(UUID uuid, JobType job, int level, int xp) {
        JobDatabase.JobData d = getData(uuid);
        d.setLevel(job, Math.max(0, Math.min(config.getMaxLevels(), level)));
        d.setXp(job, Math.max(0, xp));
        database.addXp(uuid, job, 0, d.getLevelFor(job), d.getXpFor(job));
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && packetSender != null) packetSender.sendJobData(p, d);
    }

    /** Ajoute de l'XP "admin" directement sans contrainte. */
    public void addXpAdmin(Player player, JobType job, int amount) {
        giveXp(player, job, amount);
    }

    /** Reset complet d'un métier pour un joueur. */
    public void resetJob(UUID uuid, JobType job) {
        JobDatabase.JobData d = getData(uuid);
        d.setLevel(job, 0);
        d.setXp(job, 0);
        database.addXp(uuid, job, 0, 0, 0);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && packetSender != null) packetSender.sendJobData(p, d);
    }

    // ── Vault ─────────────────────────────────────────────────────────────────

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }
}
