package fr.originsfight.packets;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PacketBuilder {
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

    private final DataOutputStream dos = new DataOutputStream(this.baos);

    public static PacketBuilder create(int packetId) {
        PacketBuilder pb = new PacketBuilder();
        pb.writeVarInt(packetId);
        return pb;
    }

    public PacketBuilder writeVarInt(int value) {
        try {
            while ((value & 0xFFFFFF80) != 0) {
                this.dos.writeByte(value & 0x7F | 0x80);
                value >>>= 7;
            }
            this.dos.writeByte(value & 0x7F);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeLong(long value) {
        try {
            this.dos.writeLong(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeDouble(double value) {
        try {
            this.dos.writeDouble(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeByte(byte value) {
        try {
            this.dos.writeByte(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeBoolean(boolean value) {
        try {
            this.dos.writeBoolean(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        try {
            this.dos.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public PacketBuilder writeBytes(byte[] data) {
        try {
            this.dos.write(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public byte[] buildRaw() {
        try {
            this.dos.flush();
        } catch (IOException iOException) {}
        return this.baos.toByteArray();
    }

    public byte[] build() {
        return buildRaw();
    }
}
