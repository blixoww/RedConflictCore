package fr.originsfight;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.originsfight.automsg.AutoMessageManager;
import fr.originsfight.bounty.BountyCommand;
import fr.originsfight.bounty.BountyListener;
import fr.originsfight.bounty.BountyManager;
import fr.originsfight.friend.FriendCommand;
import fr.originsfight.friend.FriendListener;
import fr.originsfight.friend.FriendManager;
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
import fr.originsfight.ks.KsCommand;
import fr.originsfight.ks.KsDatabase;
import fr.originsfight.ks.KsListener;
import fr.originsfight.listeners.*;
import fr.originsfight.listeners.WelcomeListener;
import fr.originsfight.packets.CustomPacketServerHandler;
import fr.originsfight.repair.RepairCommand;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import fr.originsfight.staff.StaffPlugin;
import fr.originsfight.trade.TradeCommand;
import fr.originsfight.trade.TradeListener;
import fr.originsfight.useful.BaltopCommand;
import fr.originsfight.useful.CobbleCommand;
import fr.originsfight.useful.CommandsCommand;
import fr.originsfight.useful.FurnaceCommand;
import fr.originsfight.useful.MsgCommand;
import fr.originsfight.useful.PoubelleCommand;
import fr.originsfight.useful.VisionCommand;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;

    private WorldGuardPlugin worldGuard;

    private List<String> disabledInCombatCommands;

    private List<String> alwaysDisabledCommands;

    private final Map<Material, ItemStack> smeltableItems = new HashMap<>();

    private HdvManager hdvManager;
    private ShopManager shopManager;
    private StaffPlugin staffPlugin;
    private KsDatabase ksDatabase;
    private BountyManager bountyManager;
    private FriendManager friendManager;
    private LotoManager lotoManager;

    public void onEnable() {
        instance = this;
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §aRedConflict est activé !");

        this.worldGuard = (WorldGuardPlugin)getServer().getPluginManager().getPlugin("WorldGuard");
        saveDefaultConfig();
        loadRecipes();
        this.alwaysDisabledCommands = getConfig().getStringList("commands.always-disabled");
        this.disabledInCombatCommands = getConfig().getStringList("commands.disabled-in-combat");
        registerCommands();
        registerListeners();
        loadPackets();
        // Système staff
        this.staffPlugin = new StaffPlugin(this);
        if (!this.staffPlugin.enable()) {
            getLogger().severe("[Staff] Échec de l'initialisation du système staff !");
        }
        // Système KS (kill score / stats PvP)
        this.ksDatabase = new KsDatabase(this);
        if (this.ksDatabase.init()) {
            KsCommand ksCmd = new KsCommand(ksDatabase);
            getCommand("ks").setExecutor(ksCmd);
            getCommand("ks").setTabCompleter(ksCmd);
            getServer().getPluginManager().registerEvents(new KsListener(ksDatabase), this);
            getLogger().info("[KS] Système KS initialisé avec succès !");
        } else {
            getLogger().severe("[KS] Échec de l'initialisation du système KS !");
        }
        // Système de primes (bounty)
        this.bountyManager = new BountyManager();
        if (this.bountyManager.enable(this)) {
            getLogger().info("[Bounty] Système de primes initialisé avec succès !");
        } else {
            getLogger().severe("[Bounty] Échec de l'initialisation du système de primes !");
        }
        BountyCommand bountyCmd = new BountyCommand(bountyManager);
        getCommand("prime").setExecutor(bountyCmd);
        getCommand("prime").setTabCompleter(bountyCmd);
        getServer().getPluginManager().registerEvents(new BountyListener(bountyManager), this);
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
    }

    public void onDisable() {
        getServer().getConsoleSender().sendMessage("§6[RedConflict] §cRedConflict est désactivé !");
        if (this.hdvManager != null)     this.hdvManager.disable();
        if (this.shopManager != null)    this.shopManager.disable();
        if (this.staffPlugin != null)    this.staffPlugin.disable();
        if (this.bountyManager != null)  this.bountyManager.disable();
        if (this.friendManager != null)  this.friendManager.disable();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long joinTime = KsListener.getJoinTime(p.getUniqueId());
            if (joinTime != null) {
                long seconds = (System.currentTimeMillis() - joinTime) / 1000;
                ksDatabase.addPlaytime(p.getUniqueId(), seconds);
            }
        }
        if (this.ksDatabase  != null) this.ksDatabase.close();
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
        // Messagerie privée
        MsgCommand msg = new MsgCommand();
        getCommand("msg").setExecutor(msg);
        getCommand("msg").setTabCompleter(msg);
        getCommand("r").setExecutor(msg);
        getCommand("msgspy").setExecutor(msg);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new RTPListener(), this);
        getServer().getPluginManager().registerEvents(new CombatLogListener(), this);
        getServer().getPluginManager().registerEvents(new VoidListener(), this);
        getServer().getPluginManager().registerEvents(new DeathMessages(), this);
        getServer().getPluginManager().registerEvents(new DisabledCommands(), this);
        getServer().getPluginManager().registerEvents(new FallProtectionListener(), this);
        getServer().getPluginManager().registerEvents(new HdvLoginListener(this), this);
        getServer().getPluginManager().registerEvents(new WelcomeListener(), this);
        getServer().getPluginManager().registerEvents(new PoubelleCommand(), this);
        getServer().getPluginManager().registerEvents(new BottleXpListener(), this);
        getServer().getPluginManager().registerEvents(new TradeListener(), this);
        getServer().getPluginManager().registerEvents(new GiveAllListener(), this);
        CobbleCommand cobbleListener = new CobbleCommand();
        getCommand("cobble").setExecutor(cobbleListener);
        getServer().getPluginManager().registerEvents(cobbleListener, this);
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
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:SHOP_C2S",
            (PluginMessageListener) new ShopServerHandler(this));
        getServer().getMessenger().registerIncomingPluginChannel(this, "CUSTOM:PDATA_C2S", (PluginMessageListener)new fr.originsfight.data.PlayerDataServerHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:HDV_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:SHOP_S2C");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "CUSTOM:PDATA_S2C");
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

    public static OriginsFightCore getInstance() {
        return instance;
    }

    public KsDatabase getKsDatabase() {
        return this.ksDatabase;
    }

    public Economy getEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("[Vault] Vault n'est pas installé ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        Economy econ = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
        if (econ == null) {
            getLogger().severe("[Vault] Aucune implémentation d'Economy trouvée ! Certaines fonctionnalités seront désactivées.");
            return null;
        }
        return econ;
    }
}
