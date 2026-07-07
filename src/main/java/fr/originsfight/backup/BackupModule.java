package fr.originsfight.backup;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module backup : sauvegarde automatique de la base H2 (active uniquement sur
 * l'hôte, le Faction) et commande staff /dbbackup.
 */
public class BackupModule implements Module {

    private final OriginsFightCore plugin;
    private BackupManager manager;

    public BackupModule(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Backup";
    }

    @Override
    public void enable() {
        this.manager = new BackupManager(plugin);
        this.manager.start();
        new CommandRegistrar(plugin).register("dbbackup", new BackupCommand(plugin, manager));
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.stop();
        }
    }
}
