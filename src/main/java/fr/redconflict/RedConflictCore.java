package fr.redconflict;

import fr.redconflict.core.text.RC;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.redconflict.annonyme.AnonymeManager;
import fr.redconflict.annonyme.AnonymeModule;
import fr.redconflict.announce.AnnounceModule;
import fr.redconflict.automsg.AutoMessageModule;
import fr.redconflict.backup.BackupModule;
import fr.redconflict.bottlexp.BottleXpModule;
import fr.redconflict.bounty.BountyModule;
import fr.redconflict.boutique.OffresManager;
import fr.redconflict.boutique.PBShopModule;
import fr.redconflict.clearlagg.ClearLaggModule;
import fr.redconflict.combatlog.CombatLogModule;
import fr.redconflict.core.FeatureToggles;
import fr.redconflict.core.ModuleManager;
import fr.redconflict.core.command.RedCommand;
import fr.redconflict.data.PlayerDataModule;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.db.Database;
import fr.redconflict.db.PlayerDataDatabase;
import fr.redconflict.db.PlayerDataSyncService;
import fr.redconflict.db.PlayerLockListener;
import fr.redconflict.db.PlayerLockService;
import fr.redconflict.death.DeathModule;
import fr.redconflict.essentials.EssentialsModule;
import fr.redconflict.faction.FactionHook;
import fr.redconflict.faction.FactionModule;
import fr.redconflict.friend.FriendModule;
import fr.redconflict.giveall.GiveAllModule;
import fr.redconflict.hdv.HdvModule;
import fr.redconflict.job.JobManager;
import fr.redconflict.job.JobModule;
import fr.redconflict.lagswitch.LagSwitchModule;
import fr.redconflict.listeners.GameplayRulesModule;
import fr.redconflict.loto.LotoModule;
import fr.redconflict.anticheat.AntiCheatModule;
import fr.redconflict.packets.PacketCoreModule;
import fr.redconflict.pb.PBManager;
import fr.redconflict.pb.PBModule;
import fr.redconflict.pb.StaffAlertManager;
import fr.redconflict.repair.RepairModule;
import fr.redconflict.ring.RingModule;
import fr.redconflict.rtp.RtpModule;
import fr.redconflict.server.ServerSwitchModule;
import fr.redconflict.shop.ShopModule;
import fr.redconflict.boutique.BoutiqueCatalog;
import fr.redconflict.boutique.RewardDispatcher;
import fr.redconflict.site.EntitlementService;
import fr.redconflict.site.OrderService;
import fr.redconflict.site.SiteBridgeModule;
import fr.redconflict.site.SiteDatabase;
import fr.redconflict.staff.StaffModule;
import fr.redconflict.trade.TradeModule;
import fr.redconflict.useful.MessagingModule;
import fr.redconflict.useful.UtilityModule;
import fr.redconflict.worldborder.WorldBorderModule;
import fr.redconflict.xpboost.XpBoostModule;
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
 * injection ; voir {@link fr.redconflict.core.Module}).
 *
 * <p>Les accesseurs en bas de classe délèguent aux modules : ils existent pour
 * le code des domaines qui résout ses dépendances via {@link #getInstance()}.
 */
public class RedConflictCore extends JavaPlugin {

    private static RedConflictCore instance;

    private Database database;
    private AntiCheatModule antiCheatModule;
    private PlayerLockService playerLockService;
    private PlayerDataSyncService playerDataSync;
    private fr.redconflict.db.HandoffService handoff;
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
    private SiteDatabase siteDatabase;
    private SiteBridgeModule siteBridgeModule;
    private fr.redconflict.vote.VoteModule voteModule;

    @Override
    public void onEnable() {
        instance = this;
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §aRedConflict est activé !");

        hookWorldGuard();
        saveDefaultConfig();
        FactionHook.init(this);
        this.features = new FeatureToggles(this);
        setupDatabase();
        installModules();
        applyPermissionMessages();
        features.applyDefaults();
    }

    @Override
    public void onDisable() {
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §cRedConflict est désactivé !");

        // Dernier instantané vers le site, pendant que les deux bases sont encore
        // ouvertes. Le temps de jeu ne s'écrit qu'à cet instant, et la tâche périodique du
        // miroir ne tournera plus : sans ce passage, la dernière session de chaque joueur
        // resterait invisible sur le site jusqu'au prochain démarrage.
        flushFinalSnapshot();

        if (this.modules != null) this.modules.disableAll();

        if (this.playerDataSync != null) this.playerDataSync.saveAll(Bukkit.getOnlinePlayers());
        if (this.playerLockService != null && this.database != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.playerLockService.release(player.getUniqueId(), this.database.getServerId());
            }
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        // Le pont vers le site est fermé par ModuleManager.disableAll() ;
        // il lit dans H2, donc il passe avant la fermeture de celle-ci.
        if (this.database != null) this.database.close();
    }


    /**
     * Reporte le temps de jeu des joueurs connectés, puis pousse un dernier instantané.
     *
     * <p>Jamais bloquant pour l'arrêt : toute erreur est journalisée et l'extinction
     * continue. Un classement en retard de cinq minutes ne vaut pas un serveur qui refuse
     * de s'éteindre.
     */
    private void flushFinalSnapshot() {
        try {
            if (this.playerDataModule != null) this.playerDataModule.flushPlaytime();
            if (this.siteBridgeModule != null) this.siteBridgeModule.syncNow();
        } catch (Throwable t) {
            getLogger().warning("[SiteSync] Dernier instantané impossible : " + t);
        }
    }

    /**
     * Installe les modules dans l'ordre des dépendances : socle d'abord
     * (essentials fournit le provider économie Vault consommé par la suite),
     * puis gameplay, client moddé, données joueurs et systèmes autonomes.
     */
    private void installModules() {
        this.modules = new ModuleManager(this);

        // Anti-triche EN PREMIER : son garde de canaux doit exister avant tout
        // module qui enregistre un canal entrant, et il n'a lui-même aucune
        // dépendance. Un canal enregistré avant lui serait un canal non gardé.
        this.antiCheatModule = new AntiCheatModule(this);
        modules.install(antiCheatModule);

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
        modules.install(new WorldBorderModule(this));
        modules.install(new RepairModule(this));
        modules.install(new BottleXpModule(this));
        modules.install(new GiveAllModule(this));
        this.utilityModule = new UtilityModule(this);
        modules.install(utilityModule);
        modules.install(new MessagingModule(this));
        modules.install(new ServerSwitchModule(this));
        modules.install(new AnnounceModule(this));

        // Client moddé (canaux packet et features dédiées)
        modules.install(new PacketCoreModule(this, antiCheatModule.getGuard()));
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
        this.pbModule = new PBModule(this, playerDataModule.getPlayerDatabase(), siteDatabase);
        modules.install(pbModule);
        this.pbShopModule = new PBShopModule(this);
        modules.install(pbShopModule);
        // Après la boutique : le pont publie son catalogue et lit ses articles.
        this.siteBridgeModule = new SiteBridgeModule(this, siteDatabase);
        modules.install(siteBridgeModule);
        // Après la boutique : le vote remet ses lots avec le même distributeur.
        this.voteModule = new fr.redconflict.vote.VoteModule(this);
        modules.install(voteModule);

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

        this.handoff = new fr.redconflict.db.HandoffService(
                this.playerLockService, this.playerDataSync, this.database.getServerId());

        getServer().getPluginManager().registerEvents(
                new PlayerLockListener(this.playerLockService, this.playerDataSync, this.handoff,
                        this.database.getServerId(), this.database.isKickOnConflict(),
                        this.database.getLockWaitMillis()), this);

        // Pont vers la base du site. Ouvert ici et non dans SiteBridgeModule :
        // le ledger PB en a besoin dès l'installation de PBModule, qui vient
        // avant. Désactivé par défaut, et à n'activer que sur UN serveur de la
        // grappe : Faction et Minage lisent la même base H2, les deux
        // écriraient les mêmes lignes pour rien.
        this.siteDatabase = new SiteDatabase(this);
        this.siteDatabase.start();
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


    public static RedConflictCore getInstance() {
        return instance;
    }


    /**
     * Garde des canaux entrants du client moddé.
     *
     * <p>Toute poignée enregistrée par {@code registerIncomingPluginChannel}
     * doit passer par {@code getChannelGuard().wrap(...)} : c'est ce qui applique
     * le plafond de taille et de débit avant qu'un octet reçu ne soit lu.
     */

    /**
     * Remise de relais avant un transfert inter-serveurs. Voir
     * {@link fr.redconflict.db.HandoffService}.
     */
    public fr.redconflict.db.HandoffService getHandoff() {
        return handoff;
    }
    /** Le module anti-triche, pour les poignées qui doivent le consulter. */
    public AntiCheatModule getAntiCheat() {
        return antiCheatModule;
    }

    public fr.redconflict.anticheat.ChannelGuard getChannelGuard() {
        return antiCheatModule == null ? null : antiCheatModule.getGuard();
    }
    /** Provider central de connexions H2 (pool HikariCP). */
    public Database getCoreDatabase() {
        return this.database;
    }

    /**
     * Service de ban HWID / refus de VM. Renseigne par StaffPlugin a l'enable ;
     * consulte paresseusement par la poignee de paquet HWID (peut etre null si le
     * systeme staff n'est pas encore pret ou desactive).
     */
    private volatile fr.redconflict.staff.HwidBanService hwidBanService;

    public fr.redconflict.staff.HwidBanService getHwidBanService() {
        return this.hwidBanService;
    }

    public void setHwidBanService(fr.redconflict.staff.HwidBanService service) {
        this.hwidBanService = service;
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

    public BoutiqueCatalog getBoutiqueCatalog() {
        return pbShopModule != null ? pbShopModule.getCatalog() : null;
    }

    public RewardDispatcher getRewardDispatcher() {
        return pbShopModule != null ? pbShopModule.getRewards() : null;
    }

    public PBShopModule getPbShopModule() {
        return pbShopModule;
    }

    public SiteDatabase getSiteDatabase() {
        return siteDatabase;
    }

    public SiteBridgeModule getSiteBridge() {
        return siteBridgeModule;
    }

    public fr.redconflict.vote.VoteModule getVoteModule() {
        return voteModule;
    }

    /** {@code null} tant que le pont vers le site n'est pas actif. */
    public EntitlementService getEntitlementService() {
        return siteBridgeModule != null ? siteBridgeModule.getEntitlements() : null;
    }

    public OrderService getOrderService() {
        return siteBridgeModule != null ? siteBridgeModule.getOrders() : null;
    }

}
