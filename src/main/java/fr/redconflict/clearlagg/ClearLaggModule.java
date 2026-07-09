package fr.redconflict.clearlagg;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;

/**
 * Module clearlagg : nettoyage périodique des entités et commande /clearlagg.
 */
public class ClearLaggModule implements Module {

    private final RedConflictCore plugin;
    private ClearLaggManager manager;

    public ClearLaggModule(RedConflictCore plugin) {
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
