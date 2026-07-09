package fr.originsfight.trade;

import fr.originsfight.RedConflictCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module trade : échange sécurisé d'items entre joueurs, rendu côté client
 * moddé. Clics serveur-autoritaires via les canaux C2S/S2C dédiés.
 */
public class TradeModule implements Module {

    private final RedConflictCore plugin;

    public TradeModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Trade";
    }

    @Override
    public void enable() {
        TradeManager manager = new TradeManager();
        new CommandRegistrar(plugin).register("trade", new TradeCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new TradeListener(manager), plugin);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, TradeC2SHandler.CHANNEL_C2S, new TradeC2SHandler(plugin, manager));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, TradePacketSender.CHANNEL_S2C);
    }
}
