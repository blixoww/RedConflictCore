package fr.originsfight.bottlexp;

import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/** Module bottlexp : embouteillage de niveaux d'XP (/bottlexp) et consommation des fioles. */
public class BottleXpModule implements Module {

    private final JavaPlugin plugin;

    public BottleXpModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "BottleXp";
    }

    @Override
    public void enable() {
        new CommandRegistrar(plugin).register("bottlexp", new BottleXpCommand(plugin));
        plugin.getServer().getPluginManager().registerEvents(new BottleXpListener(), plugin);
    }
}
