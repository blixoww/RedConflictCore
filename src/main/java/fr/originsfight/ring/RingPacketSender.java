package fr.originsfight.ring;

import fr.originsfight.packets.PacketBuilder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Envoie des paquets ring S2C vers le client.
 *
 * S2C RING_SLOT_SYNC (0xD0) :
 *   VarInt packetId
 *   8 × (VarInt len | bytes ItemStack)
 */
public class RingPacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:RING_S2C";
    private static final int   RING_SLOT_SYNC = 0xD0;

    private final JavaPlugin plugin;
    private final RingManager manager;

    public RingPacketSender(JavaPlugin plugin, RingManager manager) {
        this.plugin  = plugin;
        this.manager = manager;
    }

    /** Envoie le snapshot complet des 8 slots au joueur. */
    public void sendSync(Player player) {
        ItemStack[] slots = manager.getSlots(player.getUniqueId());
        PacketBuilder pb = PacketBuilder.create(RING_SLOT_SYNC);
        for (int i = 0; i < RingManager.RING_SIZE; i++) {
            byte[] itemBytes = serializeItem(slots[i]);
            pb.writeVarInt(itemBytes.length);
            pb.writeBytes(itemBytes);
        }
        player.sendPluginMessage(plugin, CHANNEL_S2C, pb.build());
    }

    // ── Sérialisation ────────────────────────────────────────────────────────

    private byte[] serializeItem(ItemStack item) {
        if (item == null) return new byte[]{0}; // null → 1 octet = 0
        try {
            String v = org.bukkit.Bukkit.getServer().getClass()
                    .getPackage().getName().split("\\.")[3];
            Class<?> unpooled    = Class.forName("io.netty.buffer.Unpooled");
            Class<?> byteBuf     = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> pds         = Class.forName("net.minecraft.server." + v + ".PacketDataSerializer");
            Class<?> craftStack  = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsStack    = Class.forName("net.minecraft.server." + v + ".ItemStack");

            Object buf    = unpooled.getMethod("buffer").invoke(null);
            Object serial = pds.getDeclaredConstructors()[0].newInstance(buf);
            Object nms    = craftStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            // PacketDataSerializer.a(ItemStack) = writeItemStack
            pds.getMethod("a", nmsStack).invoke(serial, nms);
            int len = (int) byteBuf.getMethod("writerIndex").invoke(buf);
            byte[] bytes = new byte[len];
            byteBuf.getMethod("getBytes", int.class, byte[].class).invoke(buf, 0, bytes);
            return bytes;
        } catch (Exception e) {
            plugin.getLogger().warning("[RingPacketSender] serializeItem: " + e.getMessage());
            return new byte[]{0};
        }
    }
}
