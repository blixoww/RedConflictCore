package fr.redconflict.repair;

import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/** Module repair : /repairall (réparation de tous les items, cooldown 24 h). */
public class RepairModule implements Module {

    private final JavaPlugin plugin;

    public RepairModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Repair";
    }

    @Override
    public void enable() {
        new CommandRegistrar(plugin).register("repairall", new RepairCommand(plugin));
    }
}
