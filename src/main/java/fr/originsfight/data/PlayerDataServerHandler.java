package fr.originsfight.data;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.packets.PacketReader;
import java.io.IOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class PlayerDataServerHandler implements PluginMessageListener {
    private final OriginsFightCore plugin;

    public PlayerDataServerHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            if (packetId == 88)
                sendAllPlayerData(player);
        } catch (IOException e) {
            this.plugin.getLogger().severe("[PlayerData] Erreur : " + e.getMessage());
        }
    }

    public static void sendAllPlayerData(Player player) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        String rank = "Joueur";
        long balance = 1000L;
        int kills = 0;
        int deaths = 0;
        int playTimeMin = 0;
        byte[] data = PacketBuilder.create(82).writeString(rank).writeLong(balance).writeVarInt(kills).writeVarInt(deaths).writeVarInt(playTimeMin).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    public static void sendBalance(Player player, long balance) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        byte[] data = PacketBuilder.create(80).writeLong(balance).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    public static void sendRank(Player player, String rank) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        byte[] data = PacketBuilder.create(81).writeString(rank).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }
}
