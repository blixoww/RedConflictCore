package fr.originsfight;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.originsfight.annonyme.AnonymeCommand;
import fr.originsfight.annonyme.AnonymeListener;
import fr.originsfight.annonyme.AnonymeManager;
import fr.originsfight.automsg.AutoMessageManager;
import fr.originsfight.bounty.BountyCommand;
import fr.originsfight.bounty.BountyListener;
import fr.originsfight.bounty.BountyManager;
import fr.originsfight.bounty.KillstreakManager;
import fr.originsfight.faction.FactionDataSender;
import fr.originsfight.faction.FactionZoneSender;
import fr.originsfight.friend.FriendCommand;
import fr.originsfight.friend.FriendListener;
import fr.originsfight.friend.FriendManager;
import fr.originsfight.clearlagg.ClearLaggCommand;
import fr.originsfight.clearlagg.ClearLaggManager;
import fr.originsfight.lagswitch.LagSwitchCommand;
import fr.originsfight.lagswitch.LagSwitchListener;
import fr.originsfight.lagswitch.LagSwitchManager;
import fr.originsfight.loto.LotoCommand;
import fr.originsfight.loto.LotoManager;
import fr.originsfight.bottlexp.BottleXpCommand;
import fr.originsfight.bottlexp.BottleXpListener;
import fr.originsfight.combatlog.CombatLogCommand;
import fr.originsfight.combatlog.CombatLogListener;
import fr.originsfight.death.DeathMessages;
import fr.originsfight.giveall.GiveAllCommand;
import fr.originsfight.giveall.GiveAllListener;
import fr.originsfight.hdv.HdvCommand;
import fr.originsfight.hdv.HdvLoginListener;
import fr.originsfight.hdv.HdvManager;
import fr.originsfight.hdv.HdvServerHandler;
import fr.originsfight.shop.ShopCommand;
import fr.originsfight.shop.ShopManager;
import fr.originsfight.shop.ShopServerHandler;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.pb.PBCommand;
import fr.originsfight.pb.PBLogger;
import fr.originsfight.pb.PBManager;
import fr.originsfight.pb.StaffAlertManager;
import fr.originsfight.boutique.BoutiqueCommand;
import fr.originsfight.boutique.BoutiqueClientServerHandler;
import fr.originsfight.boutique.BoutiquePacketSender;
import fr.originsfight.boutique.OffresManager;
import fr.originsfight.ks.KsCommand;
import fr.originsfight.ks.KsListener;
import fr.originsfight.listeners.*;
import fr.originsfight.listeners.WelcomeListener;
import fr.originsfight.ping.PingServerHandler;
import fr.originsfight.packets.CustomPacketServerHandler;
import fr.originsfight.repair.RepairCommand;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import fr.originsfight.staff.StaffPlugin;
import fr.originsfight.trade.TradeCommand;
import fr.originsfight.trade.TradeListener;
import fr.originsfight.profil.ProfilCommand;
import fr.originsfight.useful.BaltopCommand;
import fr.originsfight.useful.CobbleCommand;
import fr.originsfight.useful.CommandsCommand;
import fr.originsfight.useful.FurnaceCommand;
import fr.originsfight.useful.GuideCommand;
import fr.originsfight.useful.MsgCommand;
import fr.originsfight.useful.PoubelleCommand;
import fr.originsfight.useful.VisionCommand;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;

    private WorldGuardPlugin worldGuard;

    private List<String> disabledInCombatCommands;

    private List<String> alwaysDisabledCommands;

    private final Map<Material, ItemStack> smeltableItems = new HashMap<>();

    private HdvManager hdvManager;
    private ShopManager shopManager;
    private StaffPlugin staffPlugin;
    private PlayerDatabase playerDatabase;
    private BountyManager bountyManager;
    private FriendManager friendManager;
    private LotoManager lotoManager;
    private LagSwitchManager lagSwitchManager;
    private ClearLaggManager clearLaggManager;
    private AnonymeManager anonymeManager; // Added AnonymeManager
    private PBManager pbManager;
    private PBLogger pbLogger;
    private StaffAlertManager pbStaffAlerts;
    private OffresManager offresManager;
    private FileConfiguration boutiqueConfig;

    public void onEnable() {
        instance = this;
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §aRedConflict est activé !");

        this.worldGuard = (WorldGuardPlugin)getServer().getPluginManager().getPlugin("WorldGuard");
        saveDefaultConfig();
        saveBoutiqueConfig();
        loadBoutiqueConfig();
        loadRecipes();
        this.alwaysDisabledCommands = getConfig().getStringList("commands.always-disabled");
        this.disabledInCombatCommands = getConfig().getStringList("commands.disabled-in-combat");
        
        // Initialize AnonymeManager before registering commands
        this.anonymeManager = new AnonymeManager(this); // Initialize AnonymeManager

        registerCommands();
        applyPermissionMessages();
        registerListeners();
        registerTradeChannels();
        loadPackets();
        // Système anti lag-switch
        this.lagSwitchManager = new LagSwitchManager(this);
        this.lagSwitchManager.enable();
        LagSwitchCommand lsCmd = new LagSwitchCommand(this, this.lagSwitchManager);
        if (getCommand("lagswitch") != null) {
            getCommand("lagswitch").setExecutor(lsCmd);
            getCommand("lagswitch").setTabCompleter(lsCmd);
        }
        getServer().getPluginManager().registerEvents(
                new LagSwitchListener(this, this.lagSwitchManager), this);
        // Système staff
        this.staffPlugin = new StaffPlugin(this);
        if (!this.staffPlugin.enable()) {
            getLogger().severe("[Staff] Échec de l'initialisation du système staff !");
        }
        // Système KS (kill score / stats PvP) — données centralisées dans PlayerDatabase
        this.playerDatabase = new PlayerDatabase(this);
        if (this.playerDatabase.init()) {
            KsCommand ksCmd = new KsCommand(playerDatabase);
            getCommand("ks").setExecutor(ksCmd);
            getCommand("ks").setTabCompleter(ksCmd);
            getServer().getPluginManager().registerEvents(new KsListener(playerDatabase, this), this);
            // Commande /profil (fiche publique complète d'un joueur — ouvre le GUI client)
            ProfilCommand profilCmd = new ProfilCommand(this);
            getCommand("profil").setExecutor(profilCmd);
            getCommand("profil").setTabCompleter(profilCmd);
            getLogger().info("[PlayerDB] Base de données joueurs initialisée avec succès !");

            // ── Système Points Boutique (PB) ─────────────────────────────────
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

            // ── /pbshop (boutique en inventaire) ─────────────────────────────
            this.offresManager = new OffresManager(this);
            this.offresManager.start();
            BoutiqueCommand bCmd = new BoutiqueCommand(this);
            if (getCommand("pbshop") != null) {
                getCommand("pbshop").setExecutor(bCmd);
                getCommand("pbshop").setTabCompleter(bCmd);
            }
            // Boutique client-side : canaux packet (S2C / C2S)
            getServer().getMessenger().registerIncomingPluginChannel(this,
                    BoutiqueClientServerHandler.CHANNEL_C2S,
                    new BoutiqueClientServerHandler(this));
            getServer().getMessenger().registerOutgoingPluginChannel(this,
                    BoutiquePacketSender.CHANNEL_S2C);
            getLogger().info("[PBShop] Boutique PB (client-side) initialisée ("
                    + this.offresManager.listIds().size() + " offres définies).");
        } else {
            getLogger().severe("[PlayerDB] Échec de l'initialisation de la base de données joueurs !");
        }
        // Système de primes (bounty) + killstreak
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
        BountyListener bountyListener = new BountyListener(bountyManager, killstreakManager);
        getServer().getPluginManager().registerEvents(bountyListener, this);
        getServer().getPluginManager().registerEvents(bountyManager.getFactionTracker(), this);
        // Système d'amis (friend)
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
        // Système de loto automatique
        this.lotoManager = new LotoManager(this);
        this.lotoManager.startScheduler();
        LotoCommand lotoCmd = new LotoCommand(lotoManager);
        getCommand("loto").setExecutor(lotoCmd);
        getCommand("loto").setTabCompleter(lotoCmd);
        getLogger().info("[Loto] Système de loto initialisé avec succès !");
        // Messages automatiques dans le chat
        new AutoMessageManager(this);
        // Système ClearLagg
        this.clearLaggManager = new ClearLaggManager(this);
        this.clearLaggManager.enable();
        ClearLaggCommand clCmd = new ClearLaggCommand(this, this.clearLaggManager);
        if (getCommand("clearlagg") != null) {
            getCommand("clearlagg").setExecutor(clCmd);
            getCommand("clearlagg").setTabCompleter(clCmd);
        }
    }

    public void onDisable() {
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §cRedConflict est désactivé !");
        if (this.lagSwitchManager  != null) this.lagSwitchManager.disable();
        if (this.clearLaggManager  != null) this.clearLaggManager.disable();
        if (this.hdvManager != null)     this.hdvManager.disable();
        if (this.shopManager != null) {
            fr.originsfight.shop.ShopEventManager eMgr = fr.originsfight.shop.ShopEventManager.getInstance();
            if (eMgr != null) eMgr.disable();
            this.shopManager.disable();
        }
        if (this.staffPlugin != null)    this.staffPlugin.disable();
        if (this.bountyManager != null)  this.bountyManager.disable();
        if (this.friendManager != null)  this.friendManager.disable();
        if (this.anonymeManager != null) this.anonymeManager.disable();
        if (this.offresManager != null) this.offresManager.stop();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long joinTime = KsListener.getJoinTime(p.getUniqueId());
            if (joinTime != null) {
                long seconds = (System.currentTimeMillis() - joinTime) / 1000;
                playerDatabase.addPlaytime(p.getUniqueId(), seconds);
            }
        }
        if (this.playerDatabase != null) this.playerDatabase.close();
        unloadPackets();
    }

    private void registerCommands() {
        getCommand("rtp").setExecutor(new RTPCommand());
        getCommand("ct").setExecutor(new CombatLogCommand());
        getCommand("repairall").setExecutor(new RepairCommand());
        // Poubelle : implémente aussi Listener (InventoryCloseEvent) → enregistré dans registerListeners
        PoubelleCommand poubelleCommand = new PoubelleCommand();
        getCommand("poubelle").setExecutor(poubelleCommand);
        getCommand("furnace").setExecutor(new FurnaceCommand());
        // BottleXP
        getCommand("bottlexp").setExecutor(new BottleXpCommand());
        // Trade
        TradeCommand tradeCommand = new TradeCommand();
        getCommand("trade").setExecutor(tradeCommand);
        getCommand("trade").setTabCompleter(tradeCommand);
        // GiveAll (staff uniquement)
        getCommand("giveall").setExecutor(new GiveAllCommand());
        // Baltop custom (nécessite Vault)
        BaltopCommand baltop = new BaltopCommand();
        getCommand("baltop").setExecutor(baltop);
        getCommand("baltop").setTabCompleter(baltop);
        // Vision nocturne
        getCommand("vision").setExecutor(new VisionCommand());
        // Liste des commandes
        getCommand("commands").setExecutor(new CommandsCommand());
        // Guide du serveur → ouvre le GuiCraftGuide côté client modifié
        getCommand("guide").setExecutor(new GuideCommand(this));
        // Messagerie privée
        MsgCommand msg = new MsgCommand();
        getCommand("msg").setExecutor(msg);
        getCommand("msg").setTabCompleter(msg);
        getCommand("r").setExecutor(msg);
        getCommand("msgspy").setExecutor(msg);
        // Anonyme command
        AnonymeCommand anonymeCommand = new AnonymeCommand(anonymeManager);
        if (getCommand("annonyme") != null) {
            getCommand("annonyme").setExecutor(anonymeCommand);
            getCommand("annonyme").setTabCompleter(anonymeCommand);
        }
    }

    private void registerTradeChannels() {
        getServer().getMessenger().registerIncomingPluginChannel(
                this, fr.originsfight.trade.TradeC2SHandler.CHANNEL_C2S,
                new fr.originsfight.trade.TradeC2SHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, fr.originsfight.trade.TradePacketSender.CHANNEL_S2C);
        getLogger().info("[Trade] Canaux trade enregistrés.");
    }

    private void applyPermissionMessages() {
        getDescription().getCommands().keySet().forEach(name -> {
            org.bukkit.command.PluginCommand cmd = getCommand(name);
            if (cmd != null && cmd.getPermission() != null) {
                cmd.setPermissionMessage(RC.ERR_NO_PERM);
            }
        });
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new RTPListener(), this);
        getServer().getPluginManager().registerEvents(new CombatLogListener(), this);
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
        CobbleCommand cobbleListener = new CobbleCommand();
        getCommand("cobble").setExecutor(cobbleListener);
        getServer().getPluginManager().registerEvents(cobbleListener, this);
        // Register AnonymeListener
        getServer().getPluginManager().registerEvents(new AnonymeListener(anonymeManager), this);
    }

    private void loadPackets() {
        this.hdvManager = new HdvManager(this);
        if (!this.hdvManager.enable()) {
            getLogger().severe("[HDV] Erreur lors de l'initialisation de l'HDV !");
        } else {
            getLogger().info("[HDV] HdvManager initialisavec succès !)");
        }
        HdvCommand hdvCmd = new HdvCommand(this.hdvManager);
        if (getCommand("hdv") != null) {
            getCommand("hdv").setExecutor(hdvCmd);
            getCommand("hdv").setTabCompleter(hdvCmd);
        }
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:HDV_C2S", (PluginMessageListener)new HdvServerHandler(this));
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:C2S", (PluginMessageListener)new CustomPacketServerHandler(this));
        // Shop
        this.shopManager = new ShopManager(this);
        if (!this.shopManager.enable()) {
            getLogger().severe("[Shop] Erreur lors de l'initialisation du Shop !");
        } else {
            getLogger().info("[Shop] ShopManager initialise avec succes !");
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
        // ── Événements boursiers (krach, inflation, aubaines) ────────────────
        fr.originsfight.shop.ShopEventManager eventMgr =
                new fr.originsfight.shop.ShopEventManager(this, this.shopManager);
        this.shopManager.setEventManager(eventMgr);
        eventMgr.enable();
        fr.originsfight.shop.ShopEventCommand eventCmd =
                new fr.originsfight.shop.ShopEventCommand(eventMgr, this.shopManager.getDatabase());
        if (getCommand("shopevent") != null) {
            getCommand("shopevent").setExecutor(eventCmd);
            getCommand("shopevent").setTabCompleter(eventCmd);
        }
        // Notifier les joueurs à la connexion
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                fr.originsfight.shop.ShopEventManager mgr = fr.originsfight.shop.ShopEventManager.getInstance();
                if (mgr != null) {
                    // Notif chat différée pour ne pas spammer pendant le join
                    org.bukkit.Bukkit.getScheduler().runTaskLater(OriginsFightCore.this,
                        () -> mgr.notifyJoin(e.getPlayer()), 40L);
                }
            }
        }, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:SHOP_C2S",
            (PluginMessageListener) new ShopServerHandler(this));
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:PDATA_C2S", (PluginMessageListener)new fr.originsfight.data.PlayerDataServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:HDV_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:SHOP_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:PDATA_S2C");
        // Système de Ping
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:PING_C2S",
            (PluginMessageListener) new PingServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:PING_S2C");
        // Données de faction (tag + relation) envoyées périodiquement aux clients proches
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:FACTION_S2C");
        new FactionDataSender(this).start();
        // Zone (claim) – envoie au client la faction propriétaire du chunk courant
        FactionZoneSender zoneSender = new FactionZoneSender(this);
        getServer().getPluginManager().registerEvents(zoneSender, this);
        // Tâche périodique : détecte les changements de zone sans déplacement de chunk
        zoneSender.startPeriodicUpdate();
        getLogger().info("[CustomPackets] Canaux enregistré avec succès !");
    }

    private void unloadPackets() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    public void loadRecipes() {
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe recipe = it.next();
            if (recipe instanceof FurnaceRecipe) {
                FurnaceRecipe furnaceRecipe = (FurnaceRecipe)recipe;
                Material inputMaterial = furnaceRecipe.getInput().getType();
                ItemStack resultItem = furnaceRecipe.getResult();
                if (inputMaterial != null && resultItem != null)
                    this.smeltableItems.put(inputMaterial, resultItem);
            }
        }
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

    public WorldGuardPlugin getWorldGuard() {
        return this.worldGuard;
    }

    public HdvManager getHdvManager() {
        return this.hdvManager;
    }

    public LagSwitchManager getLagSwitchManager() {
        return this.lagSwitchManager;
    }

    public static OriginsFightCore getInstance() {
        return instance;
    }

    public PlayerDatabase getPlayerDatabase() {
        return this.playerDatabase;
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

    private void saveBoutiqueConfig() {
        File file = new File(getDataFolder(), "boutique.yml");
        if (!file.exists()) saveResource("boutique.yml", false);
    }

    private void loadBoutiqueConfig() {
        File file = new File(getDataFolder(), "boutique.yml");
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream defStream = getResource("boutique.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            loaded.setDefaults(defaults);
        }
        this.boutiqueConfig = loaded;
    }

    public void reloadBoutiqueConfig() {
        loadBoutiqueConfig();
    }

    /** @deprecated Utiliser {@link #getPlayerDatabase()} */
    @Deprecated
    public PlayerDatabase getKsDatabase() {
        return this.playerDatabase;
    }

    public AnonymeManager getAnonymeManager() {
        return this.anonymeManager;
    }

    public Economy getEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("[Vault] Vault n'est pas installé ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        org.bukkit.plugin.RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
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
