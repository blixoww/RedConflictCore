package fr.originsfight.shop;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Module shop : boutique serveur à prix dynamiques (/shop, /sellall),
 * événements boursiers (krach, inflation, aubaine) et canaux du client moddé.
 */
public class ShopModule implements Module {

    private final OriginsFightCore plugin;
    private ShopManager manager;

    public ShopModule(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Shop";
    }

    @Override
    public void enable() throws Exception {
        this.manager = new ShopManager(plugin);
        if (!manager.enable()) {
            this.manager = null;
            throw new IllegalStateException("Erreur lors de l'initialisation du Shop");
        }

        CommandRegistrar commands = new CommandRegistrar(plugin);
        ShopCommand shopCommand = new ShopCommand(plugin, manager);
        commands.register(shopCommand, "shop", "shopdebug");
        commands.register("sellall", new SellAllCommand(plugin, manager));

        // Événements boursiers (krach, inflation, aubaines).
        ShopEventManager eventManager = new ShopEventManager(plugin, manager);
        manager.setEventManager(eventManager);
        eventManager.enable();
        commands.register("shopevent", new ShopEventCommand(plugin, eventManager, manager.getDatabase()));

        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                ShopEventManager current = ShopEventManager.getInstance();
                if (current != null) {
                    // Notification différée pour ne pas spammer pendant le join.
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> current.notifyJoin(event.getPlayer()), 40L);
                }
            }
        }, plugin);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, "CUSTOM:SHOP_C2S", new ShopServerHandler(plugin));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:SHOP_S2C");
    }

    @Override
    public void disable() {
        if (manager != null) {
            ShopEventManager eventManager = ShopEventManager.getInstance();
            if (eventManager != null) {
                eventManager.disable();
            }
            manager.disable();
        }
    }
}
