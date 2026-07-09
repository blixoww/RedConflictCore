package fr.redconflict.listeners;

import fr.redconflict.core.Module;
import fr.redconflict.core.Reloadable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Règles de jeu transverses : protection vide/chute, message de bienvenue et
 * blocage de commandes (listes {@code commands.always-disabled} et
 * {@code commands.disabled-in-combat} de config.yml, rechargeables à chaud).
 */
public class GameplayRulesModule implements Module, Reloadable {

    private final JavaPlugin plugin;

    private List<String> alwaysDisabled;
    private List<String> disabledInCombat;

    public GameplayRulesModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "GameplayRules";
    }

    @Override
    public void enable() {
        reload();
        plugin.getServer().getPluginManager().registerEvents(new VoidListener(), plugin);
        plugin.getServer().getPluginManager().registerEvents(new FallProtectionListener(), plugin);
        plugin.getServer().getPluginManager().registerEvents(new WelcomeListener(), plugin);
        plugin.getServer().getPluginManager().registerEvents(new DisabledCommands(this), plugin);
    }

    @Override
    public void reload() {
        this.alwaysDisabled = plugin.getConfig().getStringList("commands.always-disabled");
        this.disabledInCombat = plugin.getConfig().getStringList("commands.disabled-in-combat");
    }

    public List<String> getAlwaysDisabledCommands() {
        return alwaysDisabled;
    }

    public List<String> getDisabledInCombatCommands() {
        return disabledInCombat;
    }
}
