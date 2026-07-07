package fr.originsfight.core;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Gestionnaire de cycle de vie des {@link Module modules}.
 *
 * <p>Chaque module est activé immédiatement lors de son {@link #install(Module)} —
 * l'ordre d'installation dans le bootstrap définit donc l'ordre des dépendances.
 * Les erreurs d'activation sont isolées : un module en échec est loggé mais ne bloque
 * pas les suivants. La désactivation se fait en ordre inverse de l'activation.
 */
public class ModuleManager {

    private final Plugin plugin;
    private final List<Module> enabled = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    public ModuleManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Active le module et l'enregistre pour la désactivation.
     *
     * @return le module lui-même (permet au bootstrap de conserver une référence typée)
     */
    public <T extends Module> T install(T module) {
        try {
            module.enable();
            enabled.add(module);
        } catch (Throwable t) {
            failed.add(module.getName());
            plugin.getLogger().log(Level.SEVERE,
                    "[Module] Échec d'activation du module " + module.getName(), t);
        }
        return module;
    }

    /** Désactive tous les modules actifs, en ordre inverse d'activation. */
    public void disableAll() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            Module module = enabled.get(i);
            try {
                module.disable();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE,
                        "[Module] Erreur à la désactivation du module " + module.getName(), t);
            }
        }
        enabled.clear();
    }

    /** Recharge la configuration de tous les modules {@link Reloadable}. */
    public int reloadAll() {
        int count = 0;
        for (Module module : enabled) {
            if (!(module instanceof Reloadable)) continue;
            try {
                ((Reloadable) module).reload();
                count++;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE,
                        "[Module] Erreur au rechargement du module " + module.getName(), t);
            }
        }
        return count;
    }

    public List<Module> getEnabledModules() {
        return Collections.unmodifiableList(enabled);
    }

    public List<String> getFailedModules() {
        return Collections.unmodifiableList(failed);
    }
}
