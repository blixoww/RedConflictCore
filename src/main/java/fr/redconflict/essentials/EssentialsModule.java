package fr.redconflict.essentials;

import fr.redconflict.core.Module;
import fr.redconflict.core.Reloadable;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.core.text.Text;
import fr.redconflict.db.Database;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.economy.EcoCommand;
import fr.redconflict.essentials.command.economy.MoneyCommand;
import fr.redconflict.essentials.command.economy.PayCommand;
import fr.redconflict.essentials.command.item.AnvilCommand;
import fr.redconflict.essentials.command.item.ClearCommand;
import fr.redconflict.essentials.command.item.EnchantCommand;
import fr.redconflict.essentials.command.item.EnderchestCommand;
import fr.redconflict.essentials.command.item.GiveCommand;
import fr.redconflict.essentials.command.item.HatCommand;
import fr.redconflict.essentials.command.item.InvseeCommand;
import fr.redconflict.essentials.command.item.MoreCommand;
import fr.redconflict.essentials.command.item.WorkbenchCommand;
import fr.redconflict.essentials.command.player.FeedCommand;
import fr.redconflict.essentials.command.player.FlyCommand;
import fr.redconflict.essentials.command.player.GamemodeCommand;
import fr.redconflict.essentials.command.player.GodCommand;
import fr.redconflict.essentials.command.player.HealCommand;
import fr.redconflict.essentials.command.player.KillCommand;
import fr.redconflict.essentials.command.player.PotionCommand;
import fr.redconflict.essentials.command.player.SpeedCommand;
import fr.redconflict.essentials.command.player.XpCommand;
import fr.redconflict.essentials.command.social.HelpCommand;
import fr.redconflict.essentials.command.social.IgnoreCommand;
import fr.redconflict.essentials.command.social.ListCommand;
import fr.redconflict.essentials.command.social.NearCommand;
import fr.redconflict.essentials.command.social.SeenCommand;
import fr.redconflict.essentials.command.teleport.BackCommand;
import fr.redconflict.essentials.command.teleport.DelHomeCommand;
import fr.redconflict.essentials.command.teleport.DelWarpCommand;
import fr.redconflict.essentials.command.teleport.HomeCommand;
import fr.redconflict.essentials.command.teleport.HomeOfCommand;
import fr.redconflict.essentials.command.teleport.SetHomeCommand;
import fr.redconflict.essentials.command.teleport.TpHereCommand;
import fr.redconflict.essentials.command.teleport.SetSpawnCommand;
import fr.redconflict.essentials.command.teleport.SetWarpCommand;
import fr.redconflict.essentials.command.teleport.SpawnCommand;
import fr.redconflict.essentials.command.teleport.TopCommand;
import fr.redconflict.essentials.command.teleport.TpAcceptCommand;
import fr.redconflict.essentials.command.teleport.TpCommand;
import fr.redconflict.essentials.command.teleport.TpDenyCommand;
import fr.redconflict.essentials.command.teleport.TpaCommand;
import fr.redconflict.essentials.command.teleport.TpaHereCommand;
import fr.redconflict.essentials.command.teleport.WarpCommand;
import fr.redconflict.essentials.command.world.WeatherCommand;
import fr.redconflict.essentials.config.EssentialsConfig;
import fr.redconflict.essentials.economy.CoreEconomyProvider;
import fr.redconflict.essentials.economy.EssentialsImporter;
import fr.redconflict.essentials.listener.BackDeathListener;
import fr.redconflict.essentials.listener.ConnectionListener;
import fr.redconflict.essentials.listener.GodListener;
import fr.redconflict.essentials.listener.IgnoreChatListener;
import fr.redconflict.essentials.listener.InvseeListener;
import fr.redconflict.essentials.listener.TeleportGuardListener;
import fr.redconflict.essentials.listener.WeatherLockListener;
import fr.redconflict.essentials.repository.h2.H2AccountRepository;
import fr.redconflict.essentials.repository.h2.H2BackRepository;
import fr.redconflict.essentials.repository.h2.H2HomeRepository;
import fr.redconflict.essentials.repository.h2.H2IgnoreRepository;
import fr.redconflict.essentials.repository.h2.H2PlayerStateRepository;
import fr.redconflict.essentials.repository.h2.H2SeenRepository;
import fr.redconflict.essentials.repository.h2.H2SpawnRepository;
import fr.redconflict.essentials.repository.h2.H2WarpRepository;
import fr.redconflict.essentials.service.BackService;
import fr.redconflict.essentials.service.CooldownService;
import fr.redconflict.essentials.service.EconomyService;
import fr.redconflict.essentials.service.HomeService;
import fr.redconflict.essentials.service.IgnoreService;
import fr.redconflict.essentials.service.InvseeSessions;
import fr.redconflict.essentials.service.PlayerStateService;
import fr.redconflict.essentials.service.SeenService;
import fr.redconflict.essentials.service.SpawnService;
import fr.redconflict.essentials.service.TeleportRequestService;
import fr.redconflict.essentials.service.TeleportService;
import fr.redconflict.essentials.service.WarpService;
import fr.redconflict.essentials.service.WeatherService;
import fr.redconflict.essentials.service.resolve.EnchantmentResolver;
import fr.redconflict.essentials.service.resolve.ItemResolver;
import fr.redconflict.essentials.service.resolve.PotionResolver;
import fr.redconflict.useful.TpuCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Module "essentials" : commandes essentielles intégrées au Core, remplaçant
 * EssentialsX (téléportation, homes/warps, items, états joueur, économie,
 * social, météo).
 *
 * <p>Câblage par injection : repositories H2 → services métier → commandes.
 * Les commandes ne contiennent aucune logique métier ; les listeners routent
 * les événements vers les services. Configuration dans {@code essentials.yml},
 * rechargeable via {@code /red reload}.
 */
public class EssentialsModule implements Module, Reloadable {

    /** Purge des cooldowns expirés toutes les 5 minutes (6000 ticks). */
    private static final long COOLDOWN_PURGE_TICKS = 6000L;

    private final JavaPlugin plugin;
    private final Database database;

    private EssentialsConfig config;
    private CoreEconomyProvider economyProvider;
    private EssentialsImporter importer;

    public EssentialsModule(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "Essentials";
    }

    @Override
    public void enable() {
        this.config = new EssentialsConfig(plugin);
        Logger logger = plugin.getLogger();
        String serverId = database.getServerId();

        // ── Repositories (persistance H2) ──────────────────────────────────────
        // Homes/warps/spawn/back sont locaux à chaque serveur du cluster (serverId) ;
        // seen, ignore, états et soldes sont partagés.
        H2HomeRepository homeRepo = new H2HomeRepository(database, serverId, logger);
        H2WarpRepository warpRepo = new H2WarpRepository(database, serverId, logger);
        H2SpawnRepository spawnRepo = new H2SpawnRepository(database, serverId, logger);
        H2BackRepository backRepo = new H2BackRepository(database, serverId, logger);
        H2SeenRepository seenRepo = new H2SeenRepository(database, logger);
        H2IgnoreRepository ignoreRepo = new H2IgnoreRepository(database, logger);
        H2PlayerStateRepository stateRepo = new H2PlayerStateRepository(database, logger);
        H2AccountRepository accountRepo = new H2AccountRepository(database, logger);
        homeRepo.init();
        warpRepo.init();
        spawnRepo.init();
        backRepo.init();
        seenRepo.init();
        ignoreRepo.init();
        stateRepo.init();
        accountRepo.init();

        // ── Services métier ────────────────────────────────────────────────────
        CooldownService cooldowns = new CooldownService();
        BackService backService = new BackService(backRepo);
        TeleportService teleports = new TeleportService(plugin, config, cooldowns, backService);
        // /tpu (TP Unavailable) décide du blocage des demandes, sans couplage.
        TeleportRequestService requests = new TeleportRequestService(config, teleports, TpuCommand::shouldBlock);
        HomeService homes = new HomeService(homeRepo, config);
        WarpService warps = new WarpService(warpRepo);
        SpawnService spawns = new SpawnService(spawnRepo);
        PlayerStateService states = new PlayerStateService(stateRepo, config);
        IgnoreService ignores = new IgnoreService(ignoreRepo);
        SeenService seen = new SeenService(seenRepo);
        WeatherService weather = new WeatherService();
        EconomyService economy = new EconomyService();
        InvseeSessions invsee = new InvseeSessions();
        ItemResolver items = new ItemResolver();
        EnchantmentResolver enchantments = new EnchantmentResolver();
        PotionResolver potions = new PotionResolver();

        // ── Provider économie Vault (remplace celui d'EssentialsX) ─────────────
        if (config.economyEnabled() && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            this.economyProvider = new CoreEconomyProvider(
                    accountRepo, config.startingBalance(), config.currencySymbol());
            Bukkit.getServicesManager().register(Economy.class, economyProvider, plugin, ServicePriority.Highest);
            logger.info("[Essentials] Provider économie Vault enregistré (priorité Highest).");
        }
        this.importer = new EssentialsImporter(plugin, accountRepo, homeRepo, seenRepo);

        // ── Commandes (déclarées dans plugin.yml) ──────────────────────────────
        CommandEnvironment env = new CommandEnvironment(plugin, config, cooldowns);
        CommandRegistrar commands = new CommandRegistrar(plugin);
        // Téléportation
        commands.register("spawn", new SpawnCommand(env, spawns, teleports));
        commands.register("setspawn", new SetSpawnCommand(env, spawns));
        commands.register("tpa", new TpaCommand(env, requests));
        commands.register("tpaccept", new TpAcceptCommand(env, requests));
        commands.register("tpno", new TpDenyCommand(env, requests));
        commands.register("tpahere", new TpaHereCommand(env, requests));
        commands.register("tphere", new TpHereCommand(env, teleports));
        commands.register("tp", new TpCommand(env, teleports));
        commands.register("top", new TopCommand(env, teleports));
        commands.register("back", new BackCommand(env, backService, teleports));
        commands.register("home", new HomeCommand(env, homes, teleports));
        commands.register("homes", new HomeOfCommand(env, homes, seen, teleports));
        commands.register("sethome", new SetHomeCommand(env, homes));
        commands.register("delhome", new DelHomeCommand(env, homes));
        commands.register("warp", new WarpCommand(env, warps, teleports));
        commands.register("setwarp", new SetWarpCommand(env, warps));
        commands.register("delwarp", new DelWarpCommand(env, warps));
        // Inventaire / items
        commands.register("give", new GiveCommand(env, items));
        commands.register("more", new MoreCommand(env));
        commands.register("clear", new ClearCommand(env));
        commands.register("enchant", new EnchantCommand(env, enchantments));
        commands.register("anvil", new AnvilCommand(env));
        commands.register("craft", new WorkbenchCommand(env));
        commands.register("ec", new EnderchestCommand(env));
        commands.register("hat", new HatCommand(env));
        commands.register("invsee", new InvseeCommand(env, invsee));
        // Joueur / statut
        commands.register("feed", new FeedCommand(env));
        commands.register("heal", new HealCommand(env));
        commands.register("kill", new KillCommand(env));
        commands.register("god", new GodCommand(env, states));
        commands.register("fly", new FlyCommand(env, states));
        commands.register("speed", new SpeedCommand(env));
        commands.register("gm", new GamemodeCommand(env));
        commands.register("xp", new XpCommand(env));
        commands.register("potion", new PotionCommand(env, potions));
        // Économie
        commands.register("pay", new PayCommand(env, economy, seen));
        commands.register("money", new MoneyCommand(env, economy, seen));
        commands.register("eco", new EcoCommand(env, economy, seen));
        // Social / utilitaire
        commands.register("ignore", new IgnoreCommand(env, ignores, seen));
        commands.register("near", new NearCommand(env));
        commands.register("seen", new SeenCommand(env, seen));
        commands.register("list", new ListCommand(env));
        commands.register("help", new HelpCommand(env));
        // Monde
        commands.register("weather", new WeatherCommand(env, weather));

        // ── Listeners (un par domaine) ─────────────────────────────────────────
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new ConnectionListener(seen, ignores, states, teleports, requests, invsee, economyProvider), plugin);
        pm.registerEvents(new TeleportGuardListener(teleports), plugin);
        pm.registerEvents(new GodListener(states), plugin);
        pm.registerEvents(new BackDeathListener(backService, config), plugin);
        pm.registerEvents(new IgnoreChatListener(ignores), plugin);
        pm.registerEvents(new fr.redconflict.essentials.listener.ChatColorListener(), plugin);
        pm.registerEvents(new InvseeListener(invsee), plugin);
        pm.registerEvents(new WeatherLockListener(weather), plugin);

        Bukkit.getScheduler().runTaskTimer(plugin, cooldowns::purgeExpired,
                COOLDOWN_PURGE_TICKS, COOLDOWN_PURGE_TICKS);
        logger.info("[Essentials] Module actif : 41 commandes, 7 listeners.");
    }

    @Override
    public void disable() {
        // Les tâches et listeners sont libérés par Bukkit ; seul le service Vault
        // doit être désenregistré explicitement.
        if (economyProvider != null) {
            Bukkit.getServicesManager().unregister(Economy.class, economyProvider);
            economyProvider = null;
        }
    }

    @Override
    public void reload() {
        if (config != null) {
            config.reload();
        }
    }

    /** Import des données EssentialsX (soldes, homes, seen) — /red import essentials. */
    public void runImport(CommandSender sender, boolean force) {
        if (importer == null) {
            sender.sendMessage(Text.error("Import indisponible (module essentials inactif)."));
            return;
        }
        importer.runAsync(sender, force);
    }
}
