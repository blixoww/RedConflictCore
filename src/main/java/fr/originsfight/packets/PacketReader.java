package fr.originsfight.packets;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class PacketReader {
    private final byte[] data;

    private int pos;

    public PacketReader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public int readVarInt() throws IOException {
        int value = 0, shift = 0;
        while (true) {
            if (this.pos >= this.data.length)
                throw new IOException("VarInt EOF");
            byte b = this.data[this.pos++];
            value |= (b & Byte.MAX_VALUE) << shift;
            if ((b & 0x80) == 0)
                return value;
            shift += 7;
            if (shift >= 35)
                throw new IOException("VarInt trop grand");
        }
    }

    public long readLong() throws IOException {
        ensureAvailable(8);
        long v = 0L;
        for (int i = 0; i < 8; ) {
            v = v << 8L | (this.data[this.pos++] & 0xFF);
            i++;
        }
        return v;
    }

    public boolean readBoolean() throws IOException {
        ensureAvailable(1);
        return
                (this.data[this.pos++] != 0);
    }

    public short readShort() throws IOException {
        ensureAvailable(2);
        short v = (short) ((this.data[this.pos] & 0xFF) << 8 | this.data[this.pos + 1] & 0xFF);
        this.pos += 2;
        return v;
    }

    public byte readByte() throws IOException {
        ensureAvailable(1);
        return this.data[this.pos++];
    }

    public byte[] readBytes(int length) throws IOException {
        ensureAvailable(length);
        byte[] buf = new byte[length];
        System.arraycopy(this.data, this.pos, buf, 0, length);
        this.pos += length;
        return buf;
    }

    public String readString(int maxLength) throws IOException {
        int len = readVarInt();
        if (len < 0 || len > maxLength * 4)
            throw new IOException("String invalide len=" + len);
        byte[] bytes = readBytes(len);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.length() > maxLength)
            throw new IOException("String trop longue: " + s.length());
        return s;
    }

    public boolean isReadable() {
        return (this.pos < this.data.length);
    }

    public int available() {
        return this.data.length - this.pos;
    }

    private void ensureAvailable(int n) throws IOException {
        if (this.pos + n > this.data.length)
            throw new IOException("EOF: besoin=" + n + " dispo=" + (this.data.length - this.pos));
    }

    public ItemStack readItemStackNms() throws IOException {
        int remaining = this.data.length - this.pos;
        if (remaining <= 0)
            throw new IOException("Buffer vide");
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
            Class<?> unpooledCls = Class.forName("io.netty.buffer.Unpooled");
            Class<?> byteBufCls = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> pdsCls = Class.forName("net.minecraft.server." + v + ".PacketDataSerializer");
            Class<?> nmsStackCls = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> craftStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Object buf = unpooledCls.getMethod("wrappedBuffer", new Class[]{byte[].class, int.class, int.class}).invoke(null, new Object[]{this.data, Integer.valueOf(this.pos), Integer.valueOf(remaining)});
            Object pds = pdsCls.getDeclaredConstructors()[0].newInstance(new Object[]{buf});
            Method readItem = pdsCls.getMethod("i", new Class[0]);
            Object nmsStack = readItem.invoke(pds, new Object[0]);
            int consumed = ((Integer) byteBufCls.getMethod("readerIndex", new Class[0]).invoke(buf, new Object[0])).intValue();
            this.pos += consumed;
            if (nmsStack == null)
                return null;
            Method asBukkitCopy = craftStackCls.getMethod("asBukkitCopy", new Class[]{nmsStackCls});
            ItemStack result = (ItemStack) asBukkitCopy.invoke(null, new Object[]{nmsStack});
            return result;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[PacketReader] readItemStackNms: " + e.getMessage());
            return null;
        }
    }
}
