package fr.redconflict.packets;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.data.PlayerDataServerHandler;
import fr.redconflict.ping.PingServerHandler;

/**
 * Canaux génériques du client moddé : requêtes custom (CUSTOM:C2S/S2C),
 * données joueur (PDATA) et mesure de ping. Les canaux propres à un domaine
 * (HDV, shop, trade, ring, métiers...) sont enregistrés par leur module.
 */
public class PacketCoreModule implements Module {

    private final RedConflictCore plugin;

    public PacketCoreModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "PacketCore";
    }

    @Override
    public void enable() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, "CUSTOM:C2S", new CustomPacketServerHandler(plugin));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:S2C");

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, "CUSTOM:PDATA_C2S", new PlayerDataServerHandler(plugin));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:PDATA_S2C");

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, "CUSTOM:PING_C2S", new PingServerHandler(plugin));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:PING_S2C");
    }
}
