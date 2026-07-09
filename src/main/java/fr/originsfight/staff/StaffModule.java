package fr.originsfight.staff;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;

/**
 * Module staff : mode staff, vanish, sanctions (warn/kick/mute/ban),
 * chat staff, freeze... {@link StaffPlugin} câble lui-même ses commandes
 * et listeners ; ce module n'en pilote que le cycle de vie.
 */
public class StaffModule implements Module {

    private final RedConflictCore plugin;
    private StaffPlugin staffPlugin;

    public StaffModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Staff";
    }

    @Override
    public void enable() throws Exception {
        this.staffPlugin = new StaffPlugin(plugin);
        if (!staffPlugin.enable()) {
            this.staffPlugin = null;
            throw new IllegalStateException("Échec de l'initialisation du système staff");
        }
    }

    @Override
    public void disable() {
        if (staffPlugin != null) {
            staffPlugin.disable();
        }
    }
}
