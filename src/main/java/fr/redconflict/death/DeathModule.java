package fr.redconflict.death;

import fr.redconflict.annonyme.AnonymeManager;
import fr.redconflict.core.Module;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module death : messages de mort personnalisés, anonymisés quand la victime
 * ou le tueur est en /annonyme.
 */
public class DeathModule implements Module {

    private final JavaPlugin plugin;
    private final AnonymeManager anonymeManager;

    public DeathModule(JavaPlugin plugin, AnonymeManager anonymeManager) {
        this.plugin = plugin;
        this.anonymeManager = anonymeManager;
    }

    @Override
    public String getName() {
        return "Death";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(new DeathMessages(anonymeManager), plugin);
    }
}
