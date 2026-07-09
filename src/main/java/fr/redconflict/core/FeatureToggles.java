package fr.redconflict.core;

import fr.redconflict.feature.DisabledFeatureCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Activation/désactivation de fonctionnalités par serveur ({@code features.<clé>}
 * dans config.yml, absent = activé). Une commande désactivée (et ses alias)
 * répond « fonctionnalité désactivée sur ce serveur ».
 */
public class FeatureToggles {

    /**
     * Commandes désactivables par config. La clé porte le même nom que la commande.
     * Le loto n'y figure pas : son module entier est court-circuité (LotoModule).
     */
    private static final String[] TOGGLEABLE = {
            "prime", "trade", "hdv", "shop", "sellall", "metier",
            "bottlexp", "furnace", "repairall", "vision", "rtp", "baltop", "guide", "poubelle"
    };

    private final JavaPlugin plugin;
    /** Exécuteur partagé attribué aux commandes des fonctionnalités désactivées. */
    private final DisabledFeatureCommand disabledExecutor = new DisabledFeatureCommand();

    public FeatureToggles(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lit {@code features.<clé>} dans config.yml (absent = activé par défaut). */
    public boolean isEnabled(String key) {
        return plugin.getConfig().getBoolean("features." + key, true);
    }

    /**
     * Désactive une commande (et ses alias, qui partagent le même PluginCommand) :
     * son exécuteur renvoie alors « fonctionnalité désactivée sur ce serveur ».
     */
    public void disableCommand(String name) {
        PluginCommand command = plugin.getCommand(name);
        if (command != null) {
            command.setExecutor(disabledExecutor);
            command.setTabCompleter(disabledExecutor);
        }
    }

    /** Applique les toggles à toutes les commandes désactivables (après les modules). */
    public void applyDefaults() {
        for (String name : TOGGLEABLE) {
            if (!isEnabled(name)) {
                disableCommand(name);
                plugin.getLogger().info("[Features] /" + name + " désactivée (features." + name + ": false).");
            }
        }
    }
}
