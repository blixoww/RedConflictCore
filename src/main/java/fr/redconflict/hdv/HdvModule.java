package fr.redconflict.hdv;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;

/**
 * Module HDV (Hôtel des Ventes) : manager + persistance, commande /hdv,
 * notification des ventes à la connexion et canal C2S du client moddé.
 */
public class HdvModule implements Module {

    private final RedConflictCore plugin;
    private HdvManager manager;

    public HdvModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Hdv";
    }

    @Override
    public void enable() throws Exception {
        this.manager = new HdvManager(plugin);
        if (!manager.enable()) {
            this.manager = null;
            throw new IllegalStateException("Erreur lors de l'initialisation de l'HDV");
        }
        new CommandRegistrar(plugin).register("hdv", new HdvCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new HdvLoginListener(plugin), plugin);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, "CUSTOM:HDV_C2S", plugin.getChannelGuard().wrap(new HdvServerHandler(plugin)));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:HDV_S2C");
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.disable();
        }
    }
}
