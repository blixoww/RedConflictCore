package fr.originsfight.combatlog;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module combat-log : tag de combat PvP (émetteur S2C pilotant le widget du
 * client moddé) et commande /ct de consultation.
 */
public class CombatLogModule implements Module {

    private final RedConflictCore plugin;
    private CombatLogSender sender;

    public CombatLogModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "CombatLog";
    }

    @Override
    public void enable() {
        this.sender = new CombatLogSender(plugin);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CombatLogSender.CHANNEL);
        sender.start();
        plugin.getServer().getPluginManager().registerEvents(new CombatLogListener(sender), plugin);
        new CommandRegistrar(plugin).register("ct", new CombatLogCommand(plugin));
    }

    @Override
    public void disable() {
        if (sender != null) {
            sender.stop();
        }
    }
}
