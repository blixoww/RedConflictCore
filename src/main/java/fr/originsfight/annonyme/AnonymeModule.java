package fr.originsfight.annonyme;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module /annonyme : masque pseudo, faction et grade d'un joueur
 * (coordination serveur/client 1.8).
 */
public class AnonymeModule implements Module {

    private final RedConflictCore plugin;
    private AnonymeManager manager;

    public AnonymeModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Anonyme";
    }

    @Override
    public void enable() {
        this.manager = new AnonymeManager(plugin);
        new CommandRegistrar(plugin).register("annonyme", new AnonymeCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new AnonymeListener(manager), plugin);
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.disable();
        }
    }

    /** Requis par le module death (messages de mort anonymisés) et les senders faction. */
    public AnonymeManager getManager() {
        return manager;
    }
}
