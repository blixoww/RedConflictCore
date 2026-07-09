package fr.originsfight.server;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module de navigation inter-serveurs du cluster Velocity :
 * /hub, /minage et /faction (transfert via le canal BungeeCord du proxy).
 */
public class ServerSwitchModule implements Module {

    private final RedConflictCore plugin;

    public ServerSwitchModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "ServerSwitch";
    }

    @Override
    public void enable() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        CommandRegistrar commands = new CommandRegistrar(plugin);
        commands.register("hub", new ServerSwitchCommand(plugin, "hub", "§9§lHUB"));
        commands.register("minage", new ServerSwitchCommand(plugin, "minage", "§e§lMINAGE"));
        commands.register("faction", new ServerSwitchCommand(plugin, "faction", "§c§lFACTION"));
    }
}
