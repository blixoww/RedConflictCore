package fr.originsfight.data;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.ks.KsDatabase;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.packets.PacketReader;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
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

        // Rank via Vault Chat (prefix du groupe)
        String rank = "Joueur";
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) {
                Chat chat = rsp.getProvider();
                String group = chat.getPrimaryGroup(player);
                if (group != null && !group.isEmpty()) {
                    rank = group;
                }
            }
        } catch (Exception ignored) {}

        // Balance via Vault Economy
        long balance = 0L;
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            balance = (long) eco.getBalance(player);
        }

        // Kills / Deaths / Playtime via KsDatabase
        int kills = 0;
        int deaths = 0;
        int playTimeMin = 0;
        KsDatabase ksDb = plugin.getKsDatabase();
        if (ksDb != null) {
            KsDatabase.KsStats stats = ksDb.getStats(player.getUniqueId());
            if (stats != null) {
                kills = stats.kills;
                deaths = stats.deaths;
                playTimeMin = (int) (stats.playtimeSeconds / 60);
            }
        }

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
