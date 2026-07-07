package fr.originsfight.clearlagg;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module clearlagg : nettoyage périodique des entités et commande /clearlagg.
 */
public class ClearLaggModule implements Module {

    private final OriginsFightCore plugin;
    private ClearLaggManager manager;

    public ClearLaggModule(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "ClearLagg";
    }

    @Override
    public void enable() {
        this.manager = new ClearLaggManager(plugin);
        manager.enable();
        new CommandRegistrar(plugin).register("clearlagg", new ClearLaggCommand(plugin, manager));
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.disable();
        }
    }
}
