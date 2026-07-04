package fr.originsfight;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.originsfight.annonyme.AnonymeCommand;
import fr.originsfight.annonyme.AnonymeListener;
import fr.originsfight.annonyme.AnonymeManager;
import fr.originsfight.announce.AnnounceCommand;
import fr.originsfight.announce.AnnounceService;
import fr.originsfight.automsg.AutoMessageManager;
import fr.originsfight.backup.BackupCommand;
import fr.originsfight.backup.BackupManager;
import fr.originsfight.bottlexp.BottleXpCommand;
import fr.originsfight.bottlexp.BottleXpListener;
import fr.originsfight.bounty.BountyCommand;
import fr.originsfight.bounty.BountyListener;
import fr.originsfight.bounty.BountyManager;
import fr.originsfight.bounty.KillstreakManager;
import fr.originsfight.boutique.BoutiqueClientServerHandler;
import fr.originsfight.boutique.BoutiqueCommand;
import fr.originsfight.boutique.BoutiquePacketSender;
import fr.originsfight.boutique.OffresManager;
import fr.originsfight.clearlagg.ClearLaggCommand;
import fr.originsfight.clearlagg.ClearLaggManager;
import fr.originsfight.combatlog.CombatLogCommand;
import fr.originsfight.combatlog.CombatLogListener;
import fr.originsfight.combatlog.CombatLogSender;
import fr.originsfight.data.PlayerDataServerHandler;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.db.Database;
import fr.originsfight.db.PlayerDataDatabase;
import fr.originsfight.db.PlayerDataSyncService;
import fr.originsfight.db.PlayerLockListener;
import fr.originsfight.db.PlayerLockService;
import fr.originsfight.death.DeathMessages;
import fr.originsfight.faction.FactionDataSender;
import fr.originsfight.faction.FactionZoneSender;
import fr.originsfight.faction.MinimapPositionSender;
import fr.originsfight.feature.DisabledFeatureCommand;
import fr.originsfight.friend.FriendCommand;
import fr.originsfight.friend.FriendListener;
import fr.originsfight.friend.FriendManager;
import fr.originsfight.giveall.GiveAllCommand;
import fr.originsfight.giveall.GiveAllListener;
import fr.originsfight.hdv.HdvCommand;
import fr.originsfight.hdv.HdvLoginListener;
import fr.originsfight.hdv.HdvManager;
import fr.originsfight.hdv.HdvServerHandler;
import fr.originsfight.job.*;
import fr.originsfight.ks.KsCommand;
import fr.originsfight.ks.KsListener;
import fr.originsfight.lagswitch.LagSwitchCommand;
import fr.originsfight.lagswitch.LagSwitchListener;
import fr.originsfight.lagswitch.LagSwitchManager;
import fr.originsfight.listeners.*;
import fr.originsfight.loto.LotoCommand;
import fr.originsfight.loto.LotoManager;
import fr.originsfight.packets.CustomPacketServerHandler;
import fr.originsfight.pb.PBCommand;
import fr.originsfight.pb.PBLogger;
import fr.originsfight.pb.PBManager;
import fr.originsfight.pb.StaffAlertManager;
import fr.originsfight.ping.PingServerHandler;
import fr.originsfight.profil.ProfilCommand;
import fr.originsfight.repair.RepairCommand;
import fr.originsfight.ring.*;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import fr.originsfight.server.ServerSwitchCommand;
import fr.originsfight.shop.*;
import fr.originsfight.staff.StaffPlugin;
import fr.originsfight.trade.TradeC2SHandler;
import fr.originsfight.trade.TradeCommand;
import fr.originsfight.trade.TradeListener;
import fr.originsfight.trade.TradePacketSender;
import fr.originsfight.useful.*;
import fr.originsfight.xpboost.XpBoostListener;
import fr.originsfight.xpboost.XpBoostManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Point d'entrée du plugin RedConflict : initialise l'infrastructure (H2, backups),
 * les modules de jeu et les canaux de communication avec le client moddé.
 */
public class OriginsFightCore extends JavaPlugin {

    private static OriginsFightCore instance;

    // Infrastructure
    private Database database;
    private PlayerLockService playerLockService;
    private PlayerDataSyncService playerDataSync;
    private BackupManager backupManager;
    private WorldGuardPlugin worldGuard;

    // Modules de jeu
    private PlayerDatabase playerDatabase;
    private HdvManager hdvManager;
    private ShopManager shopManager;
    private StaffPlugin staffPlugin;
    private XpBoostManager xpBoostManager;
    private BountyManager bountyManager;
    private FriendManager friendManager;
    private LotoManager lotoManager;
    private LagSwitchManager lagSwitchManager;
    private ClearLaggManager clearLaggManager;
    private AnonymeManager anonymeManager;
    private PBManager pbManager;
    private PBLogger pbLogger;
    private StaffAlertManager pbStaffAlerts;
    private OffresManager offresManager;
    private RingManager ringManager;
    private JobDatabase jobDatabase;
    private JobManager jobManager;
    private JobTopManager jobTopManager;

    // Configuration
    private FileConfiguration boutiqueConfig;
    private List<String> disabledInCombatCommands;
    private List<String> alwaysDisabledCommands;
    private final Map<Material, ItemStack> smeltableItems = new HashMap<>();

    /** Exécuteur partagé attribué aux commandes des fonctionnalités désactivées. */
    private final DisabledFeatureCommand disabledFeatureCommand = new DisabledFeatureCommand();

    @Override
    public void onEnable() {
        instance = this;
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §aRedConflict est activé !");

        hookWorldGuard();
        saveDefaultConfig();

        // La base H2 doit être prête avant tout module qui persiste des données.
        setupDatabase();
        setupBackup();

        saveBoutiqueConfig();
        loadBoutiqueConfig();
        loadRecipes();
        this.alwaysDisabledCommands = getConfig().getStringList("commands.always-disabled");
        this.disabledInCombatCommands = getConfig().getStringList("commands.disabled-in-combat");

        // Requis par registerCommands() et registerListeners().
        this.anonymeManager = new AnonymeManager(this);

        registerCommands();
        applyPermissionMessages();
        registerListeners();
        registerTradeChannels();
        registerRing();
        registerJob();
        registerClientChannels();

        setupLagSwitch();
        setupStaff();
        setupPlayerData(); // câble XpBoost sur le JobManager : doit suivre registerJob()
        setupBounty();
        setupFriends();
        setupLoto();
        new AutoMessageManager(this);
        setupClearLagg();

        applyFeatureToggles();
    }

    @Override
    public void onDisable() {
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §cRedConflict est désactivé !");
        if (this.backupManager != null) this.backupManager.stop();
        if (this.lagSwitchManager != null) this.lagSwitchManager.disable();
        if (this.clearLaggManager != null) this.clearLaggManager.disable();
        if (this.jobTopManager != null) this.jobTopManager.shutdown();
        if (this.jobManager != null) {
            this.jobManager.saveAll();
            this.jobDatabase.disconnect();
        }
        if (this.hdvManager != null) this.hdvManager.disable();
        if (this.shopManager != null) {
            ShopEventManager eventManager = ShopEventManager.getInstance();
            if (eventManager != null) eventManager.disable();
            this.shopManager.disable();
        }
        if (this.staffPlugin != null) this.staffPlugin.disable();
        if (this.bountyManager != null) this.bountyManager.disable();
        if (this.friendManager != null) this.friendManager.disable();
        if (this.anonymeManager != null) this.anonymeManager.disable();
        if (this.offresManager != null) this.offresManager.stop();
        if (this.playerDatabase != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Long joinTime = KsListener.getJoinTime(p.getUniqueId());
                if (joinTime != null) {
                    long seconds = (System.currentTimeMillis() - joinTime) / 1000;
                    playerDatabase.addPlaytime(p.getUniqueId(), seconds);
                }
            }
            this.playerDatabase.close();
        }
        if (this.playerDataSync != null) this.playerDataSync.saveAll(Bukkit.getOnlinePlayers());
        if (this.playerLockService != null && this.database != null) {
            for (Player p : Bukkit.getOnlinePlayers())
                this.playerLockService.release(p.getUniqueId(), this.database.getServerId());
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        // Fermeture du pool + arrêt du serveur H2 en dernier, après toutes les sauvegardes.
        if (this.database != null) this.database.close();
    }

    // Initialisation de l'infrastructure

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

    /** Sauvegarde automatique de la base ; ne s'active que sur l'hôte H2 (le Faction). */
    private void setupBackup() {
        this.backupManager = new BackupManager(this);
        this.backupManager.start();
        if (getCommand("dbbackup") != null) {
            BackupCommand backupCmd = new BackupCommand(this, this.backupManager);
            getCommand("dbbackup").setExecutor(backupCmd);
            getCommand("dbbackup").setTabCompleter(backupCmd);
        }
    }

    // Enregistrement des commandes et listeners

    private void registerCommands() {
        getCommand("rtp").setExecutor(new RTPCommand());
        getCommand("ct").setExecutor(new CombatLogCommand());
        getCommand("repairall").setExecutor(new RepairCommand());
        getCommand("poubelle").setExecutor(new PoubelleCommand());
        getCommand("furnace").setExecutor(new FurnaceCommand());
        getCommand("bottlexp").setExecutor(new BottleXpCommand());
        getCommand("giveall").setExecutor(new GiveAllCommand());
        getCommand("vision").setExecutor(new VisionCommand());
        getCommand("commands").setExecutor(new CommandsCommand());
        getCommand("tpu").setExecutor(new TpuCommand());
        // Ouvre le GuiCraftGuide côté client moddé.
        getCommand("guide").setExecutor(new GuideCommand(this));

        TradeCommand tradeCommand = new TradeCommand();
        getCommand("trade").setExecutor(tradeCommand);
        getCommand("trade").setTabCompleter(tradeCommand);

        BaltopCommand baltop = new BaltopCommand();
        getCommand("baltop").setExecutor(baltop);
        getCommand("baltop").setTabCompleter(baltop);

        // Navigation inter-serveurs du cluster Velocity.
        ServerSwitchCommand serverSwitch = new ServerSwitchCommand(this);
        getCommand("hub").setExecutor(serverSwitch);
        getCommand("minage").setExecutor(serverSwitch);
        getCommand("faction").setExecutor(serverSwitch);

        // Annonce inter-serveurs (BungeeCord Forward) : réception + commande staff.
        AnnounceService announceService = new AnnounceService(this);
        announceService.register();
        if (getCommand("annonce") != null) {
            getCommand("annonce").setExecutor(new AnnounceCommand(announceService));
        }

        MsgCommand msg = new MsgCommand();
        getCommand("msg").setExecutor(msg);
        getCommand("msg").setTabCompleter(msg);
        getCommand("r").setExecutor(msg);
        getCommand("msgspy").setExecutor(msg);

        AnonymeCommand anonymeCommand = new AnonymeCommand(anonymeManager);
        if (getCommand("annonyme") != null) {
            getCommand("annonyme").setExecutor(anonymeCommand);
            getCommand("annonyme").setTabCompleter(anonymeCommand);
        }
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

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new RTPListener(), this);

        // Combat Tag : émetteur S2C (pilote le widget client) + listener (PvP uniquement).
        CombatLogSender combatLogSender = new CombatLogSender(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CombatLogSender.CHANNEL);
        combatLogSender.start();
        getServer().getPluginManager().registerEvents(new CombatLogListener(combatLogSender), this);

        getServer().getPluginManager().registerEvents(new VoidListener(), this);
        getServer().getPluginManager().registerEvents(new DeathMessages(anonymeManager), this);
        getServer().getPluginManager().registerEvents(new DisabledCommands(), this);
        getServer().getPluginManager().registerEvents(new FallProtectionListener(), this);
        getServer().getPluginManager().registerEvents(new HdvLoginListener(this), this);
        getServer().getPluginManager().registerEvents(new WelcomeListener(), this);
        getServer().getPluginManager().registerEvents(new PoubelleCommand(), this);
        getServer().getPluginManager().registerEvents(new BottleXpListener(), this);
        getServer().getPluginManager().registerEvents(new TradeListener(), this);
        getServer().getPluginManager().registerEvents(new GiveAllListener(), this);
        getServer().getPluginManager().registerEvents(new WeatherListener(), this);
        getServer().getPluginManager().registerEvents(new AnonymeListener(anonymeManager), this);

        // /cobble est à la fois exécuteur et listener (filtrage des drops).
        CobbleCommand cobbleListener = new CobbleCommand();
        getCommand("cobble").setExecutor(cobbleListener);
        getServer().getPluginManager().registerEvents(cobbleListener, this);
    }

    private void registerTradeChannels() {
        getServer().getMessenger().registerIncomingPluginChannel(
                this, TradeC2SHandler.CHANNEL_C2S, new TradeC2SHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, TradePacketSender.CHANNEL_S2C);
        getLogger().info("[Trade] Canaux trade enregistrés.");
    }

    private void registerRing() {
        this.ringManager = new RingManager(this);
        RingPacketSender ringPacketSender = new RingPacketSender(this, ringManager);

        getServer().getMessenger().registerIncomingPluginChannel(
                this, RingServerHandler.CHANNEL_C2S, new RingServerHandler(ringManager, ringPacketSender));
        getServer().getMessenger().registerOutgoingPluginChannel(this, RingPacketSender.CHANNEL_S2C);

        getServer().getPluginManager().registerEvents(new RingLoginListener(ringManager, ringPacketSender), this);

        RingEffects.init(ringManager);
        getServer().getPluginManager().registerEvents(new RingEffectListener(), this);
        RingEffectListener.startTask(this);

        // Drop ou conservation des rings à la mort (Totem of Undying).
        getServer().getPluginManager().registerEvents(new RingDeathListener(ringPacketSender), this);

        ringManager.startAutoSave(6000);
        getLogger().info("[Ring] Système ring initialisé.");
    }

    private void registerJob() {
        this.jobDatabase = new JobDatabase(this.database);
        if (!this.jobDatabase.connect()) {
            getLogger().severe("[Jobs] Impossible d'initialiser la base de données jobs !");
            return;
        }
        JobConfig jobConfig = new JobConfig(this);
        this.jobManager = new JobManager(this, jobDatabase, jobConfig);
        JobPacketSender jobSender = new JobPacketSender(this, jobConfig, jobDatabase);
        this.jobManager.setPacketSender(jobSender);

        // Snapshot des classements : recalcul au démarrage, toutes les 24 h et via /metier topupdate.
        this.jobTopManager = new JobTopManager(this, jobDatabase);
        this.jobManager.setTopManager(this.jobTopManager);
        this.jobTopManager.init();

        getServer().getMessenger().registerIncomingPluginChannel(
                this, JobServerHandler.CHANNEL_C2S, new JobServerHandler(this, jobManager, jobSender));
        getServer().getMessenger().registerOutgoingPluginChannel(this, JobPacketSender.CHANNEL_S2C);

        getServer().getPluginManager().registerEvents(new JobLoginListener(jobManager, jobSender), this);
        getServer().getPluginManager().registerEvents(new JobMinerListener(jobManager, jobConfig), this);
        getServer().getPluginManager().registerEvents(new JobFarmerListener(jobManager, jobConfig), this);
        getServer().getPluginManager().registerEvents(new JobArtisanListener(jobManager, jobConfig), this);

        JobCommand jobCmd = new JobCommand(this, jobManager, jobSender);
        if (getCommand("metier") != null) {
            getCommand("metier").setExecutor(jobCmd);
            getCommand("metier").setTabCompleter(jobCmd);
        }
        getLogger().info("[Jobs] Système de métiers initialisé.");
    }

    /** Enregistre les modules pilotés par packets (HDV, shop) et les canaux du client moddé. */
    private void registerClientChannels() {
        setupHdv();
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:C2S", new CustomPacketServerHandler(this));
        setupShop();
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:PDATA_C2S", new PlayerDataServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:HDV_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:SHOP_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:PDATA_S2C");
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:PING_C2S", new PingServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:PING_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:FACTION_S2C");

        // Minimap : le sender filtre avant envoi (jamais d'ennemi ni de neutre) et
        // dégrade proprement si Factions est absent (les positions d'amis restent envoyées).
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:MMAP_S2C");
        new MinimapPositionSender(this).start();

        // Transfert inter-serveurs (/hub, /minage) via le proxy.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        setupFactionFeatures();
        getLogger().info("[CustomPackets] Canaux client enregistrés.");
    }

    private void setupHdv() {
        this.hdvManager = new HdvManager(this);
        if (this.hdvManager.enable()) {
            getLogger().info("[HDV] HdvManager initialisé avec succès !");
        } else {
            getLogger().severe("[HDV] Erreur lors de l'initialisation de l'HDV !");
        }
        HdvCommand hdvCmd = new HdvCommand(this.hdvManager);
        if (getCommand("hdv") != null) {
            getCommand("hdv").setExecutor(hdvCmd);
            getCommand("hdv").setTabCompleter(hdvCmd);
        }
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:HDV_C2S", new HdvServerHandler(this));
    }

    private void setupShop() {
        this.shopManager = new ShopManager(this);
        if (this.shopManager.enable()) {
            getLogger().info("[Shop] ShopManager initialisé avec succès !");
        } else {
            getLogger().severe("[Shop] Erreur lors de l'initialisation du Shop !");
        }
        ShopCommand shopCmd = new ShopCommand(this.shopManager);
        if (getCommand("shop") != null) {
            getCommand("shop").setExecutor(shopCmd);
            getCommand("shop").setTabCompleter(shopCmd);
        }
        if (getCommand("shopdebug") != null) {
            getCommand("shopdebug").setExecutor(shopCmd);
            getCommand("shopdebug").setTabCompleter(shopCmd);
        }
        SellAllCommand sellAllCmd = new SellAllCommand(this.shopManager);
        if (getCommand("sellall") != null) {
            getCommand("sellall").setExecutor(sellAllCmd);
        }

        // Événements boursiers (krach, inflation, aubaines).
        ShopEventManager eventManager = new ShopEventManager(this, this.shopManager);
        this.shopManager.setEventManager(eventManager);
        eventManager.enable();
        ShopEventCommand eventCmd = new ShopEventCommand(eventManager, this.shopManager.getDatabase());
        if (getCommand("shopevent") != null) {
            getCommand("shopevent").setExecutor(eventCmd);
            getCommand("shopevent").setTabCompleter(eventCmd);
        }
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                ShopEventManager mgr = ShopEventManager.getInstance();
                if (mgr != null) {
                    // Notification différée pour ne pas spammer pendant le join.
                    Bukkit.getScheduler().runTaskLater(OriginsFightCore.this,
                            () -> mgr.notifyJoin(e.getPlayer()), 40L);
                }
            }
        }, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:SHOP_C2S", new ShopServerHandler(this));
    }

    /**
     * Features faction, actives uniquement si le plugin RedFaction est présent (serveur Faction).
     * Les {@code new} restent dans la branche conditionnelle pour que la JVM ne charge jamais
     * les classes dépendant de RedFaction quand celui-ci est absent (ex. serveur Minage).
     */
    private void setupFactionFeatures() {
        if (getServer().getPluginManager().getPlugin("RedFaction") == null) {
            getLogger().info("[Faction] Plugin RedFaction absent — features faction (HUD tag, zone de claim) désactivées.");
            return;
        }
        // Tag + relation de faction envoyés périodiquement aux clients proches.
        new FactionDataSender(this).start();
        // Zone (claim) : envoie au client la faction propriétaire du chunk courant.
        FactionZoneSender zoneSender = new FactionZoneSender(this);
        getServer().getPluginManager().registerEvents(zoneSender, this);
        // Détecte aussi les changements de zone sans franchissement de chunk.
        zoneSender.startPeriodicUpdate();
    }

    // Initialisation des modules de jeu

    private void setupLagSwitch() {
        this.lagSwitchManager = new LagSwitchManager(this);
        this.lagSwitchManager.enable();
        LagSwitchCommand lsCmd = new LagSwitchCommand(this, this.lagSwitchManager);
        if (getCommand("lagswitch") != null) {
            getCommand("lagswitch").setExecutor(lsCmd);
            getCommand("lagswitch").setTabCompleter(lsCmd);
        }
        getServer().getPluginManager().registerEvents(new LagSwitchListener(this, this.lagSwitchManager), this);
    }

    private void setupStaff() {
        this.staffPlugin = new StaffPlugin(this);
        if (!this.staffPlugin.enable()) {
            getLogger().severe("[Staff] Échec de l'initialisation du système staff !");
        }
    }

    /** Base joueurs (KS, /profil) et modules qui en dépendent : XpBoost, PB, boutique PB. */
    private void setupPlayerData() {
        this.playerDatabase = new PlayerDatabase(this.database);
        if (!this.playerDatabase.init()) {
            getLogger().severe("[PlayerDB] Échec de l'initialisation de la base de données joueurs !");
            return;
        }
        KsCommand ksCmd = new KsCommand(playerDatabase);
        getCommand("ks").setExecutor(ksCmd);
        getCommand("ks").setTabCompleter(ksCmd);
        getServer().getPluginManager().registerEvents(new KsListener(playerDatabase, this), this);

        // /profil : fiche publique d'un joueur, ouvre le GUI côté client moddé.
        ProfilCommand profilCmd = new ProfilCommand(this);
        getCommand("profil").setExecutor(profilCmd);
        getCommand("profil").setTabCompleter(profilCmd);
        getLogger().info("[PlayerDB] Base de données joueurs initialisée avec succès !");

        setupXpBoost();
        setupPB();
        setupPBShop();
    }

    private void setupXpBoost() {
        this.xpBoostManager = new XpBoostManager(this.playerDatabase);
        if (this.jobManager != null) {
            this.jobManager.setXpBoostManager(this.xpBoostManager);
            // Le sender métier inclut le temps de boost restant dans JOB_DATA.
            if (this.jobManager.getPacketSender() != null) {
                this.jobManager.getPacketSender().setXpBoostManager(this.xpBoostManager);
            }
        }
        getServer().getPluginManager().registerEvents(new XpBoostListener(this.xpBoostManager, this.jobManager), this);
        getLogger().info("[XpBoost] Boost d'XP x2 (item xp_booster) initialisé.");
    }

    private void setupPB() {
        this.pbLogger = new PBLogger(this);
        this.pbStaffAlerts = new StaffAlertManager(this);
        this.pbManager = new PBManager(this, this.playerDatabase, this.pbLogger, this.pbStaffAlerts);
        PBCommand pbCmd = new PBCommand(this.pbManager);
        if (getCommand("pb") != null) {
            getCommand("pb").setExecutor(pbCmd);
            getCommand("pb").setTabCompleter(pbCmd);
        }
        getLogger().info("[PB] Système Points Boutique initialisé (seuil alerte staff : "
                + this.pbStaffAlerts.getThreshold() + " PB).");
    }

    /** Boutique PB rendue côté client : /pbshop + canaux packet dédiés. */
    private void setupPBShop() {
        this.offresManager = new OffresManager(this);
        this.offresManager.start();
        BoutiqueCommand boutiqueCmd = new BoutiqueCommand(this);
        if (getCommand("pbshop") != null) {
            getCommand("pbshop").setExecutor(boutiqueCmd);
            getCommand("pbshop").setTabCompleter(boutiqueCmd);
        }
        getServer().getMessenger().registerIncomingPluginChannel(this,
                BoutiqueClientServerHandler.CHANNEL_C2S, new BoutiqueClientServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, BoutiquePacketSender.CHANNEL_S2C);
        getLogger().info("[PBShop] Boutique PB (client-side) initialisée ("
                + this.offresManager.listIds().size() + " offres définies).");
    }

    private void setupBounty() {
        KillstreakManager killstreakManager = new KillstreakManager();
        this.bountyManager = new BountyManager(killstreakManager);
        if (this.bountyManager.enable(this)) {
            getLogger().info("[Bounty] Système de primes initialisé avec succès !");
        } else {
            getLogger().severe("[Bounty] Échec de l'initialisation du système de primes !");
        }
        BountyCommand bountyCmd = new BountyCommand(bountyManager, killstreakManager);
        getCommand("prime").setExecutor(bountyCmd);
        getCommand("prime").setTabCompleter(bountyCmd);
        getServer().getPluginManager().registerEvents(new BountyListener(bountyManager, killstreakManager), this);
        getServer().getPluginManager().registerEvents(bountyManager.getFactionTracker(), this);
    }

    private void setupFriends() {
        this.friendManager = new FriendManager();
        if (this.friendManager.enable(this)) {
            getLogger().info("[Friend] Système d'amis initialisé avec succès !");
        } else {
            getLogger().severe("[Friend] Échec de l'initialisation du système d'amis !");
        }
        FriendCommand friendCmd = new FriendCommand(friendManager);
        getCommand("friend").setExecutor(friendCmd);
        getCommand("friend").setTabCompleter(friendCmd);
        getServer().getPluginManager().registerEvents(new FriendListener(friendManager), this);
    }

    private void setupLoto() {
        if (!isFeatureEnabled("loto")) {
            disableFeatureCommand("loto");
            getLogger().info("[Loto] Désactivé via la config (features.loto: false).");
            return;
        }
        this.lotoManager = new LotoManager(this);
        this.lotoManager.startScheduler();
        LotoCommand lotoCmd = new LotoCommand(lotoManager);
        getCommand("loto").setExecutor(lotoCmd);
        getCommand("loto").setTabCompleter(lotoCmd);
        getLogger().info("[Loto] Système de loto initialisé avec succès !");
    }

    private void setupClearLagg() {
        this.clearLaggManager = new ClearLaggManager(this);
        this.clearLaggManager.enable();
        ClearLaggCommand clCmd = new ClearLaggCommand(this, this.clearLaggManager);
        if (getCommand("clearlagg") != null) {
            getCommand("clearlagg").setExecutor(clCmd);
            getCommand("clearlagg").setTabCompleter(clCmd);
        }
    }

    // Toggle de fonctionnalités (features.<clé> dans config.yml)

    /**
     * Désactive les commandes des fonctionnalités marquées {@code false} sous {@code features:}.
     * La clé de config porte le même nom que la commande. Contrairement au loto (dont le manager
     * complet est court-circuité dans {@link #setupLoto()}), seule la commande est coupée ici —
     * le module sous-jacent peut continuer à tourner.
     */
    private void applyFeatureToggles() {
        String[] toggleable = {
                "prime", "trade", "hdv", "shop", "sellall", "metier",
                "bottlexp", "furnace", "repairall", "vision", "rtp", "baltop", "guide", "poubelle"
        };
        for (String name : toggleable) {
            if (!isFeatureEnabled(name)) {
                disableFeatureCommand(name);
                getLogger().info("[Features] /" + name + " désactivée (features." + name + ": false).");
            }
        }
    }

    /**
     * Indique si une fonctionnalité est activée sur ce serveur.
     * Lit {@code features.<key>} dans config.yml (absent = activé par défaut).
     */
    public boolean isFeatureEnabled(String key) {
        return getConfig().getBoolean("features." + key, true);
    }

    /**
     * Désactive une commande (et ses alias, qui partagent le même PluginCommand) :
     * son exécuteur renvoie alors « fonctionnalité désactivée sur ce serveur ».
     */
    public void disableFeatureCommand(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(disabledFeatureCommand);
            cmd.setTabCompleter(disabledFeatureCommand);
        }
    }

    // Configuration boutique et recettes

    private void saveBoutiqueConfig() {
        File file = new File(getDataFolder(), "boutique/boutique.yml");
        file.getParentFile().mkdirs();
        if (!file.exists()) saveResource("boutique/boutique.yml", false);
    }

    private void loadBoutiqueConfig() {
        File file = new File(getDataFolder(), "boutique/boutique.yml");
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream defStream = getResource("boutique.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            loaded.setDefaults(defaults);
        }
        this.boutiqueConfig = loaded;
    }

    /** Indexe les recettes de cuisson vanilla pour /furnace. */
    public void loadRecipes() {
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe recipe = it.next();
            if (recipe instanceof FurnaceRecipe) {
                FurnaceRecipe furnaceRecipe = (FurnaceRecipe) recipe;
                Material inputMaterial = furnaceRecipe.getInput().getType();
                ItemStack resultItem = furnaceRecipe.getResult();
                if (inputMaterial != null && resultItem != null)
                    this.smeltableItems.put(inputMaterial, resultItem);
            }
        }
    }

    // Accesseurs

    public static OriginsFightCore getInstance() {
        return instance;
    }

    /** Provider central de connexions H2 (pool HikariCP). */
    public Database getCoreDatabase() {
        return this.database;
    }

    public PlayerDatabase getPlayerDatabase() {
        return this.playerDatabase;
    }

    /** @deprecated Utiliser {@link #getPlayerDatabase()}. */
    @Deprecated
    public PlayerDatabase getKsDatabase() {
        return this.playerDatabase;
    }

    public WorldGuardPlugin getWorldGuard() {
        return this.worldGuard;
    }

    public HdvManager getHdvManager() {
        return this.hdvManager;
    }

    public JobManager getJobManager() {
        return this.jobManager;
    }

    public LagSwitchManager getLagSwitchManager() {
        return this.lagSwitchManager;
    }

    public AnonymeManager getAnonymeManager() {
        return this.anonymeManager;
    }

    public PBManager getPBManager() {
        return this.pbManager;
    }

    public StaffAlertManager getPBStaffAlerts() {
        return this.pbStaffAlerts;
    }

    public OffresManager getOffresManager() {
        return this.offresManager;
    }

    public FileConfiguration getBoutiqueConfig() {
        return this.boutiqueConfig;
    }

    public Map<Material, ItemStack> getSmeltableItems() {
        return this.smeltableItems;
    }

    public List<String> getDisabledInCombatCommands() {
        return this.disabledInCombatCommands;
    }

    public List<String> getAlwaysDisabledCommands() {
        return this.alwaysDisabledCommands;
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
        Economy econ = rsp.getProvider();
        if (econ == null) {
            getLogger().severe("[Vault] Aucune implémentation d'Economy trouvée ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        return econ;
    }
}
