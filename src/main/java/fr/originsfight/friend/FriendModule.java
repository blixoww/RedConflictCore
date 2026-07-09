package fr.originsfight.friend;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module friend : système d'amis (pas de dégâts entre amis, max 5) —
 * commande /friend et listener de protection.
 */
public class FriendModule implements Module {

    private final RedConflictCore plugin;
    private FriendManager manager;

    public FriendModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Friend";
    }

    @Override
    public void enable() throws Exception {
        this.manager = new FriendManager();
        if (!manager.enable(plugin)) {
            this.manager = null;
            throw new IllegalStateException("Échec de l'initialisation du système d'amis");
        }
        new CommandRegistrar(plugin).register("friend", new FriendCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new FriendListener(manager), plugin);
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.disable();
        }
    }
}
