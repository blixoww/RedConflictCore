package fr.originsfight.core.command;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point unique d'enregistrement des commandes déclarées dans plugin.yml.
 *
 * <p>Tolère l'absence d'une commande (simple warning) pour permettre de retirer
 * une entrée du plugin.yml sans casser l'activation du module concerné.
 */
public class CommandRegistrar {

    private final JavaPlugin plugin;

    public CommandRegistrar(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Associe l'exécuteur à la commande (et le tab-completer si l'exécuteur l'implémente).
     */
    public void register(String name, CommandExecutor executor) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().warning("[Commands] /" + name + " absente du plugin.yml — ignorée.");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof TabCompleter) {
            command.setTabCompleter((TabCompleter) executor);
        }
    }

    /** Enregistre le même exécuteur sur plusieurs commandes (ex. familles /tpa, /tpaccept...). */
    public void register(CommandExecutor executor, String... names) {
        for (String name : names) {
            register(name, executor);
        }
    }
}
