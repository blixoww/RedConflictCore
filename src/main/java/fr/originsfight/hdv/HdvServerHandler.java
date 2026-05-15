package fr.originsfight.hdv;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.packets.PacketReader;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class HdvServerHandler implements PluginMessageListener {
    private final OriginsFightCore plugin;

    public HdvServerHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            int page;
            ItemStack item;
            final int[] listingId = new int[1];
            String filter;
            long totalPrice;
            int quantity;
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            HdvManager manager = HdvManager.getInstance();
            if (manager == null) {
                this.plugin.getLogger().warning("[HDV] HdvManager non initialise !");
                return;
            }
            switch (packetId) {
                case 16:
                    page = reader.readVarInt();
                    filter = reader.readString(128);
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.sendListings(player, page, filter));
                    break;
                case 20:
                    listingId[0] = reader.readVarInt();
                    boolean buyerPBChoice = false;
                    try { buyerPBChoice = reader.readBoolean(); } catch (Exception ignored) {}
                    final boolean bpc = buyerPBChoice;
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.handleBuy(player, listingId[0], bpc));
                    break;
                case 18:
                    item = reader.readItemStackNms();
                    totalPrice = reader.readLong();
                    quantity = reader.readVarInt();
                    boolean payPB = reader.readBoolean();
                    long pricePBValue = 0L;
                    try { pricePBValue = reader.readLong(); } catch (Exception ignored) {}
                    if (item == null) {
                        sendActionResult(player, false, "Item invalide recu.");
                        return;
                    }
                    final boolean payPBf = payPB;
                    final long pricePBf = pricePBValue;
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.handlePostOffer(player, item, totalPrice, quantity, payPBf, pricePBf));
                    break;
                case 19:
                    listingId[0] = reader.readVarInt();
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.handleCancelOffer(player, listingId[0]));
                    break;
                case 21:
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.handleCollect(player));
                    break;
                case 0x16: // HDV_MY_LISTINGS_REQUEST
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.sendMyListings(player));
                    break;
                case 0x17: // HDV_ADMIN_EXPIRE
                    if (!player.isOp()) {
                        sendActionResult(player, false, "Permission refusée.");
                        return;
                    }
                    listingId[0] = reader.readVarInt();
                    this.plugin.getServer().getScheduler().runTask(this.plugin, () -> manager.handleForceExpire(player, listingId[0]));
                    break;
            }
        } catch (Exception exception) {
            this.plugin.getLogger().severe("Une erreur s'est produite lors de la réception d'un message de plugin : " + exception.getMessage());
            this.plugin.getLogger().severe(exception.toString());
        }
    }

    public static void sendActionResult(Player player, boolean success, String message) {
        byte[] pkt = PacketBuilder.create(34).writeBoolean(success).writeString(message).build();
        player.sendPluginMessage(OriginsFightCore.getInstance(), "CUSTOM:HDV_S2C", pkt);
    }
}
