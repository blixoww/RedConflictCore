package fr.originsfight.essentials.command;

import fr.originsfight.essentials.config.EssentialsConfig;
import fr.originsfight.essentials.service.CooldownService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Dépendances transverses de toutes les commandes essentials
 * (configuration, cooldowns, logger). Les services métier restent injectés
 * individuellement dans chaque commande concernée.
 */
public class CommandEnvironment {

    private final JavaPlugin plugin;
    private final EssentialsConfig config;
    private final CooldownService cooldowns;

    public CommandEnvironment(JavaPlugin plugin, EssentialsConfig config, CooldownService cooldowns) {
        this.plugin = plugin;
        this.config = config;
        this.cooldowns = cooldowns;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public EssentialsConfig getConfig() {
        return config;
    }

    public CooldownService getCooldowns() {
        return cooldowns;
    }
}
