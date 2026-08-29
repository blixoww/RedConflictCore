package fr.redconflict.packets;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PacketBuilder {
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

    private final DataOutputStream dos = new DataOutputStream(this.baos);

    /**
     * Ouvre un paquet sur son identifiant LOGIQUE.
     *
     * <p>L'appelant écrit {@code 0x53} ou {@code 82} comme il l'a toujours fait :
     * c'est ici, et seulement ici, que la valeur est traduite pour le fil par
     * {@link WireIds}. Aucun des fichiers qui envoient des paquets n'a à
     * connaître le brouillage.
     */
    public static PacketBuilder create(int packetId) {
        PacketBuilder pb = new PacketBuilder();
        pb.writeVarInt(WireIds.toWire(packetId));
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
