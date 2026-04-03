package fr.originsfight.shop;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketReader;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class ShopServerHandler implements PluginMessageListener {

    // Packet IDs C2S
    private static final int SHOP_CATEGORIES_REQUEST  = 0x30;
    private static final int SHOP_ITEMS_REQUEST       = 0x31;
    private static final int SHOP_BUY                 = 0x32;
    private static final int SHOP_SELL                = 0x33;
    private static final int SHOP_ITEM_DETAIL_REQUEST = 0x34;

    private final OriginsFightCore plugin;

    public ShopServerHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            ShopManager manager = ShopManager.getInstance();

            if (manager == null) {
                plugin.getLogger().warning("[Shop] ShopManager non initialise !");
                return;
            }

            switch (packetId) {
                case SHOP_CATEGORIES_REQUEST:
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> manager.sendCategories(player));
                    break;

                case SHOP_ITEMS_REQUEST: {
                    int categoryId = reader.readVarInt();
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> manager.sendItems(player, categoryId));
                    break;
                }

                case SHOP_BUY: {
                    int itemId = reader.readVarInt();
                    int quantity = reader.readVarInt();
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> manager.handleBuy(player, itemId, quantity));
                    break;
                }

                case SHOP_SELL: {
                    int itemId = reader.readVarInt();
                    int quantity = reader.readVarInt();
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> manager.handleSell(player, itemId, quantity));
                    break;
                }

                case SHOP_ITEM_DETAIL_REQUEST: {
                    int itemId = reader.readVarInt();
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> manager.sendItemDetail(player, itemId));
                    break;
                }

                default:
                    plugin.getLogger().warning("[Shop] Packet inconnu : 0x" +
                        Integer.toHexString(packetId));
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[Shop] Erreur reception message : " + e.getMessage());
        }
    }
}
