package fr.redconflict.giveall;

import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module giveall : distribution d'items à tous les joueurs (staff).
 */
public class GiveAllModule implements Module {

    private final JavaPlugin plugin;

    public GiveAllModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "GiveAll";
    }

    @Override
    public void enable() {
        new CommandRegistrar(plugin).register("giveall", new GiveAllCommand(plugin));
        plugin.getServer().getPluginManager().registerEvents(new GiveAllListener(), plugin);
    }
}
