package fr.originsfight.automsg;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;

/**
 * Module automsg : diffusion périodique de messages d'information dans le chat.
 */
public class AutoMessageModule implements Module {

    private final OriginsFightCore plugin;

    public AutoMessageModule(OriginsFightCore plugin) {
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
