package fr.originsfight.lagswitch;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module anti lag-switch : détection des joueurs exploitant les coupures
 * réseau en combat, et commande staff /lagswitch.
 */
public class LagSwitchModule implements Module {

    private final OriginsFightCore plugin;
    private LagSwitchManager manager;

    public LagSwitchModule(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "LagSwitch";
    }

    @Override
    public void enable() {
        this.manager = new LagSwitchManager(plugin);
        manager.enable();
        new CommandRegistrar(plugin).register("lagswitch", new LagSwitchCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new LagSwitchListener(plugin, manager), plugin);
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.disable();
        }
    }
}
