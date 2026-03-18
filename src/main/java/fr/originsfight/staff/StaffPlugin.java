package fr.originsfight.staff;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.staff.commands.*;
import fr.originsfight.useful.PlayerListManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

/**
 * Classe d'initialisation du système staff.
 * Appelée depuis OriginsFightCore.onEnable().
 */
public class StaffPlugin {

    private final StaffDatabase db;
    private final StaffListener listener;
    private final OriginsFightCore plugin;

    public StaffPlugin(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.db = new StaffDatabase(plugin);
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
        set("staffmode", new StaffModeCommand());
        set("vanish",    new VanishCommand());

        FreezeCommand freeze = new FreezeCommand(listener, db);
        set("freeze", freeze);

        WarnCommand      warn      = new WarnCommand(db, listener);
        KickCommand      kick      = new KickCommand(db, listener);
        MuteCommand      mute      = new MuteCommand(db, listener, false);
        MuteCommand      unmute    = new MuteCommand(db, listener, true);
        BanCommand       ban       = new BanCommand(db, listener, false);
        BanCommand       unban     = new BanCommand(db, listener, true);
        SanctionsCommand sanctions = new SanctionsCommand(db);
        UnsanctionCommand unsanction = new UnsanctionCommand(db, listener);

        set("warn",       warn);
        set("kick",       kick);
        set("mute",       mute);
        set("unmute",     unmute);
        set("ban",        ban);
        set("unban",      unban);
        set("sanctions",  sanctions);
        set("unsanction", unsanction);

        StaffChatCommand sc = new StaffChatCommand(listener);
        set("sc", sc);
        set("clearchat", new ClearChatCommand());
        set("lockchat",  new LockChatCommand());

        TopLuckCommand topLuck = new TopLuckCommand(db);
        set("topluck", topLuck);
        plugin.getServer().getPluginManager().registerEvents(topLuck, plugin);
    }

    private void set(String cmd, Object executor) {
        PluginCommand pluginCmd = plugin.getCommand(cmd);
        if (pluginCmd == null) return;
        if (executor instanceof org.bukkit.command.CommandExecutor)
            pluginCmd.setExecutor((org.bukkit.command.CommandExecutor) executor);
        if (executor instanceof TabCompleter)
            pluginCmd.setTabCompleter((TabCompleter) executor);
    }

    public StaffDatabase getDatabase()  { return db; }
    public StaffListener getListener()  { return listener; }
}



