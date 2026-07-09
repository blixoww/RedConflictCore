package fr.redconflict.automsg;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;

/**
 * Module automsg : diffusion périodique de messages d'information dans le chat.
 */
public class AutoMessageModule implements Module {

    private final RedConflictCore plugin;

    public AutoMessageModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "AutoMessage";
    }

    @Override
    public void enable() {
        new AutoMessageManager(plugin);
    }
}
