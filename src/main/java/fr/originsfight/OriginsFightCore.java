package fr.originsfight;

import fr.originsfight.core.text.RC;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.originsfight.annonyme.AnonymeManager;
import fr.originsfight.annonyme.AnonymeModule;
import fr.originsfight.announce.AnnounceModule;
import fr.originsfight.automsg.AutoMessageModule;
import fr.originsfight.backup.BackupModule;
import fr.originsfight.bottlexp.BottleXpModule;
import fr.originsfight.bounty.BountyModule;
import fr.originsfight.boutique.OffresManager;
import fr.originsfight.boutique.PBShopModule;
import fr.originsfight.clearlagg.ClearLaggModule;
import fr.originsfight.combatlog.CombatLogModule;
import fr.originsfight.core.FeatureToggles;
import fr.originsfight.core.ModuleManager;
import fr.originsfight.core.command.RedCommand;
import fr.originsfight.data.PlayerDataModule;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.db.Database;
import fr.originsfight.db.PlayerDataDatabase;
import fr.originsfight.db.PlayerDataSyncService;
import fr.originsfight.db.PlayerLockListener;
import fr.originsfight.db.PlayerLockService;
import fr.originsfight.death.DeathModule;
import fr.originsfight.essentials.EssentialsModule;
import fr.originsfight.faction.FactionModule;
import fr.originsfight.friend.FriendModule;
import fr.originsfight.giveall.GiveAllModule;
import fr.originsfight.hdv.HdvModule;
import fr.originsfight.job.JobManager;
import fr.originsfight.job.JobModule;
import fr.originsfight.lagswitch.LagSwitchModule;
import fr.originsfight.listeners.GameplayRulesModule;
import fr.originsfight.loto.LotoModule;
import fr.originsfight.packets.PacketCoreModule;
import fr.originsfight.pb.PBManager;
import fr.originsfight.pb.PBModule;
import fr.originsfight.pb.StaffAlertManager;
import fr.originsfight.repair.RepairModule;
import fr.originsfight.ring.RingModule;
import fr.originsfight.rtp.RtpModule;
import fr.originsfight.server.ServerSwitchModule;
import fr.originsfight.shop.ShopModule;
import fr.originsfight.staff.StaffModule;
import fr.originsfight.trade.TradeModule;
import fr.originsfight.useful.MessagingModule;
import fr.originsfight.useful.UtilityModule;
import fr.originsfight.xpboost.XpBoostModule;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point d'entrée du plugin RedConflict — bootstrap léger : initialise
 * l'infrastructure (H2, verrous, synchronisation) puis installe les modules
 * de jeu via le {@link ModuleManager} (un domaine = un module, câblé par
 * injection ; voir {@link fr.originsfight.core.Module}).
 *
 * <p>Les accesseurs en bas de classe délèguent aux modules : ils existent pour
 * le code des domaines qui résout ses dépendances via {@link #getInstance()}.
 */
public class OriginsFightCore extends JavaPlugin {

    private static OriginsFightCore instance;

    private Database database;
    private PlayerLockService playerLockService;
    private PlayerDataSyncService playerDataSync;
    private WorldGuardPlugin worldGuard;
    private FeatureToggles features;

    private ModuleManager modules;
    private EssentialsModule essentialsModule;
    private GameplayRulesModule rulesModule;
    private AnonymeModule anonymeModule;
    private UtilityModule utilityModule;
    private JobModule jobModule;
    private PlayerDataModule playerDataModule;
    private PBModule pbModule;
    private PBShopModule pbShopModule;

    @Override
    public void onEnable() {
        instance = this;
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §aRedConflict est activé !");

        hookWorldGuard();
        saveDefaultConfig();
        this.features = new FeatureToggles(this);
        setupDatabase();
        installModules();
        applyPermissionMessages();
        features.applyDefaults();
    }

    @Override
    public void onDisable() {
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §cRedConflict est désactivé !");

        if (this.modules != null) this.modules.disableAll();

        if (this.playerDataSync != null) this.playerDataSync.saveAll(Bukkit.getOnlinePlayers());
        if (this.playerLockService != null && this.database != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.playerLockService.release(player.getUniqueId(), this.database.getServerId());
            }
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        if (this.database != null) this.database.close();
    }


    /**
     * Installe les modules dans l'ordre des dépendances : socle d'abord
     * (essentials fournit le provider économie Vault consommé par la suite),
     * puis gameplay, client moddé, données joueurs et systèmes autonomes.
     */
    private void installModules() {
        this.modules = new ModuleManager(this);

        this.essentialsModule = new EssentialsModule(this, database);
        modules.install(essentialsModule);
        modules.install(new BackupModule(this));
        this.rulesModule = new GameplayRulesModule(this);
        modules.install(rulesModule);

        this.anonymeModule = new AnonymeModule(this);
        modules.install(anonymeModule);
        modules.install(new DeathModule(this, anonymeModule.getManager()));
        modules.install(new CombatLogModule(this));
        modules.install(new RtpModule(this));
        modules.install(new RepairModule(this));
        modules.install(new BottleXpModule(this));
        modules.install(new GiveAllModule(this));
        this.utilityModule = new UtilityModule(this);
        modules.install(utilityModule);
        modules.install(new MessagingModule(this));
        modules.install(new ServerSwitchModule(this));
        modules.install(new AnnounceModule(this));

        // Client moddé (canaux packet et features dédiées)
        modules.install(new PacketCoreModule(this));
        modules.install(new TradeModule(this));
        modules.install(new RingModule(this));
        this.jobModule = new JobModule(this, database);
        modules.install(jobModule);
        modules.install(new HdvModule(this));
        modules.install(new ShopModule(this));
        modules.install(new FactionModule(this));

        // Données joueurs et Points Boutique
        this.playerDataModule = new PlayerDataModule(this, database);
        modules.install(playerDataModule);
        modules.install(new XpBoostModule(this, playerDataModule.getPlayerDatabase(), jobModule.getJobManager()));
        this.pbModule = new PBModule(this, playerDataModule.getPlayerDatabase());
        modules.install(pbModule);
        this.pbShopModule = new PBShopModule(this);
        modules.install(pbShopModule);

        // Systèmes autonomes
        modules.install(new LagSwitchModule(this));
        modules.install(new StaffModule(this));
        modules.install(new BountyModule(this));
        modules.install(new FriendModule(this));
        modules.install(new LotoModule(this, features));
        modules.install(new AutoMessageModule(this));
        modules.install(new ClearLaggModule(this));

        // reload, état des modules, import EssentialsX.
        RedCommand redCommand = new RedCommand(this, modules,
                (sender, force) -> essentialsModule.runImport(sender, force));
        if (getCommand("red") != null) {
            getCommand("red").setExecutor(redCommand);
            getCommand("red").setTabCompleter(redCommand);
        }
    }


    /** WorldGuard est optionnel : présent sur le Faction (zones PvP), absent sur le Minage. */
    private void hookWorldGuard() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            this.worldGuard = (WorldGuardPlugin) getServer().getPluginManager().getPlugin("WorldGuard");
        } else {
            this.worldGuard = null;
            getLogger().info("[WorldGuard] Plugin absent — protection de zone PvP désactivée (combat-log toujours actif).");
        }
    }

    /** Démarre la base H2 centralisée, les verrous joueurs et la synchronisation d'inventaire. */
    private void setupDatabase() {
        this.database = new Database(this);
        if (!this.database.start()) {
            getLogger().severe("[H2] Base de données indisponible — les modules de données risquent de ne pas fonctionner.");
            return;
        }
        this.playerLockService = new PlayerLockService(this.database);
        this.playerLockService.createTable();
        this.playerLockService.releaseAllForServer(this.database.getServerId());

        if (getConfig().getBoolean("database.sync.enabled", true)) {
            PlayerDataDatabase dataDb = new PlayerDataDatabase(this.database);
            if (dataDb.init()) {
                this.playerDataSync = new PlayerDataSyncService(dataDb);
                // L'auto-save périodique borne la perte de données en cas de crash.
                int autosave = getConfig().getInt("database.sync.autosave-minutes", 3);
                this.playerDataSync.startAutoSave(this, autosave);
                getLogger().info("[Sync] Synchronisation inventaire/enderchest activée"
                        + (autosave > 0 ? " (auto-save toutes les " + autosave + " min)." : "."));
            } else {
                getLogger().severe("[Sync] Échec init table player_data — synchro inventaire désactivée.");
            }
        }

        getServer().getPluginManager().registerEvents(
                new PlayerLockListener(this.playerLockService, this.playerDataSync,
                        this.database.getServerId(), this.database.isKickOnConflict()), this);
    }

    /** Applique le message de permission standard à toutes les commandes du plugin.yml. */
    private void applyPermissionMessages() {
        getDescription().getCommands().keySet().forEach(name -> {
            PluginCommand cmd = getCommand(name);
            if (cmd != null && cmd.getPermission() != null) {
                cmd.setPermissionMessage(RC.ERR_NO_PERM);
            }
        });
    }


    public static OriginsFightCore getInstance() {
        return instance;
    }

    /** Provider central de connexions H2 (pool HikariCP). */
    public Database getCoreDatabase() {
        return this.database;
    }

    public WorldGuardPlugin getWorldGuard() {
        return this.worldGuard;
    }

    /**
     * Indique si une fonctionnalité est activée
     * ({@code features.<clé>} dans config.yml, absent = activé).
     */
    public boolean isFeatureEnabled(String key) {
        return features.isEnabled(key);
    }

    /** Désactive une commande. */
    public void disableFeatureCommand(String name) {
        features.disableCommand(name);
    }

    /** Économie Vault, ou {@code null} si Vault ou son provider Economy est absent. */
    public Economy getEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("[Vault] Vault n'est pas installé ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("[Vault] Aucun provider Economy enregistré ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        Economy economy = rsp.getProvider();
        if (economy == null) {
            getLogger().severe("[Vault] Aucune implémentation d'Economy trouvée ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        return economy;
    }

    // Délégations vers les modules (null si le module concerné a échoué).

    public PlayerDatabase getPlayerDatabase() {
        return playerDataModule != null ? playerDataModule.getPlayerDatabase() : null;
    }

    public JobManager getJobManager() {
        return jobModule != null ? jobModule.getJobManager() : null;
    }

    public AnonymeManager getAnonymeManager() {
        return anonymeModule != null ? anonymeModule.getManager() : null;
    }

    public PBManager getPBManager() {
        return pbModule != null ? pbModule.getManager() : null;
    }

    public StaffAlertManager getPBStaffAlerts() {
        return pbModule != null ? pbModule.getStaffAlerts() : null;
    }

    public OffresManager getOffresManager() {
        return pbShopModule != null ? pbShopModule.getOffresManager() : null;
    }

    public FileConfiguration getBoutiqueConfig() {
        return pbShopModule != null ? pbShopModule.getBoutiqueConfig() : null;
    }

}
