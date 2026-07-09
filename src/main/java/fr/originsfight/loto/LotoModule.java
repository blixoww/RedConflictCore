package fr.originsfight.loto;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.FeatureToggles;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module loto : tirages périodiques et commande /loto. Contrairement aux
 * commandes simplement coupées par {@link FeatureToggles}, le module entier
 * est court-circuité quand {@code features.loto: false} (aucun scheduler).
 */
public class LotoModule implements Module {

    private final RedConflictCore plugin;
    private final FeatureToggles features;

    public LotoModule(RedConflictCore plugin, FeatureToggles features) {
        this.plugin = plugin;
        this.features = features;
    }

    @Override
    public String getName() {
        return "Loto";
    }

    @Override
    public void enable() {
        if (!features.isEnabled("loto")) {
            features.disableCommand("loto");
            plugin.getLogger().info("[Loto] Désactivé via la config (features.loto: false).");
            return;
        }
        LotoManager manager = new LotoManager(plugin);
        manager.startScheduler();
        new CommandRegistrar(plugin).register("loto", new LotoCommand(plugin, manager));
    }
}
