package fr.originsfight.trade;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class TradePacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:TRADE_S2C";

    // Packet ids S2C
    static final int TRADE_OPEN   = 0xA0;
    static final int TRADE_UPDATE = 0xA1;
    static final int TRADE_CLOSE  = 0xA2;

    private TradePacketSender() {}

    public static void sendOpen(Player player, String partnerName, boolean isPlayerA) {
        byte[] payload = build(out -> {
            writeVarInt(out, TRADE_OPEN);
            writeString(out, partnerName);
            out.writeBoolean(isPlayerA);
        });
        send(player, payload);
    }

    public static void sendUpdate(Player player, List<ItemStack> myOffer, List<ItemStack> partnerOffer,
                                  boolean myConfirmed, boolean partnerConfirmed,
                                  long myMoney, long partnerMoney) {
        byte[] payload = build(out -> {
            writeVarInt(out, TRADE_UPDATE);
            out.writeBoolean(myConfirmed);
            out.writeBoolean(partnerConfirmed);
            writeVarInt(out, myOffer.size());
            for (ItemStack item : myOffer) writeItem(out, item);
            writeVarInt(out, partnerOffer.size());
            for (ItemStack item : partnerOffer) writeItem(out, item);
            out.writeLong(myMoney);
            out.writeLong(partnerMoney);
        });
        send(player, payload);
    }

    public static void sendClose(Player player, boolean success) {
        byte[] payload = build(out -> {
            writeVarInt(out, TRADE_CLOSE);
            out.writeBoolean(success);
        });
        send(player, payload);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void send(Player player, byte[] payload) {
        if (payload == null || !player.isOnline()) return;
        player.sendPluginMessage(OriginsFightCore.getInstance(), CHANNEL_S2C, payload);
    }

    private static byte[] build(CheckedConsumer<DataOutputStream> writer) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            writer.accept(dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeItem(DataOutputStream out, ItemStack item) throws IOException {
        byte[] bytes = serializeItemNms(item);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] serializeItemNms(ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            // NMS null item: short -1
            return new byte[]{ (byte)0xFF, (byte)0xFF };
        }
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> unpooledCls = Class.forName("io.netty.buffer.Unpooled");
            Object buf = unpooledCls.getMethod("buffer").invoke(null);
            Class<?> byteBufCls = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> pdsCls = Class.forName("net.minecraft.server." + v + ".PacketDataSerializer");
            Object pds = pdsCls.getDeclaredConstructors()[0].newInstance(buf);
            Class<?> craftCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsItemCls = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Object nmsItem = craftCls.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            pdsCls.getMethod("a", nmsItemCls).invoke(pds, nmsItem);
            int len = (Integer) byteBufCls.getMethod("writerIndex").invoke(buf);
            byte[] bytes = new byte[len];
            byteBufCls.getMethod("getBytes", int.class, byte[].class).invoke(buf, 0, bytes);
            return bytes;
        } catch (Exception e) {
            return new byte[]{ (byte)0xFF, (byte)0xFF };
        }
    }

    @FunctionalInterface
    interface CheckedConsumer<T> {
        void accept(T t) throws Exception;
    }
}
