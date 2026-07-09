package fr.originsfight.staff;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.command.CommandRegistrar;
import fr.originsfight.staff.commands.*;
import fr.originsfight.useful.PlayerListManager;
import org.bukkit.Bukkit;

/**
 * Classe d'initialisation du système staff.
 * Appelée depuis RedConflictCore.onEnable().
 */
public class StaffPlugin {

    private final StaffDatabase db;
    private final StaffListener listener;
    private final RedConflictCore plugin;

    public StaffPlugin(RedConflictCore plugin) {
        this.plugin = plugin;
        this.db = new StaffDatabase(plugin.getCoreDatabase());
        this.listener = new StaffListener(db, plugin);
    }

    public boolean enable() {
        if (!db.init()) return false;

        // Nettoyage des sanctions expirées toutes les 5 minutes
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                db::cleanExpired, 0L, 20L * 60 * 5);

        registerCommands();
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        // Scoreboard/tab-list amélioré
        new PlayerListManager(plugin).start();

        plugin.getLogger().info("[Staff] Systeme staff initialise avec succes !");
        return true;
    }

    public void disable() {
        db.close();
    }

    private void registerCommands() {
        CommandRegistrar commands = new CommandRegistrar(plugin);
        commands.register("staffmode", new StaffModeCommand(plugin));
        commands.register("vanish", new VanishCommand(plugin));
        commands.register("freeze", new FreezeCommand(plugin, listener, db));

        commands.register("warn", new WarnCommand(plugin, db, listener));
        commands.register("kick", new KickCommand(plugin, db, listener));
        commands.register("mute", new MuteCommand(plugin, db, listener, false));
        commands.register("unmute", new MuteCommand(plugin, db, listener, true));
        commands.register("ban", new BanCommand(plugin, db, listener, false));
        commands.register("unban", new BanCommand(plugin, db, listener, true));
        commands.register("sanctions", new SanctionsCommand(plugin, db));
        commands.register("unsanction", new UnsanctionCommand(plugin, db, listener));

        commands.register("sc", new StaffChatCommand(plugin, listener));
        commands.register("clearchat", new ClearChatCommand(plugin));
        commands.register("lockchat", new LockChatCommand(plugin));

        TopLuckCommand topLuck = new TopLuckCommand(plugin, db);
        commands.register("topluck", topLuck);
        plugin.getServer().getPluginManager().registerEvents(topLuck, plugin);
    }

    public StaffDatabase getDatabase()  { return db; }
    public StaffListener getListener()  { return listener; }
}



