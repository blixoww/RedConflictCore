package fr.redconflict.trade;

import fr.redconflict.RedConflictCore;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Paquets d'échange serveur -> client.
 *
 * <p><b>Ce fichier écrivait son propre protocole.</b> Il recopiait
 * {@code writeVarInt}, {@code writeString} et l'en-tête de paquet dans un
 * {@code DataOutputStream} au lieu de passer par {@link PacketBuilder} — et
 * c'est précisément l'en-tête qui posait problème : {@code PacketBuilder.create}
 * est le seul endroit où l'identifiant est traduit pour le fil par
 * {@code WireIds}. Écrit à la main, l'identifiant partait en clair, alors que le
 * client dépermute tout ce qu'il reçoit : la fenêtre d'échange ne recevait donc
 * plus rien depuis l'activation du brouillage.
 *
 * <p>Deuxième implémentation d'un protocole = deuxième occasion de diverger.
 * Tout passe désormais par le point de sortie commun.
 */
public final class TradePacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:TRADE_S2C";

    // Packet ids S2C
    static final int TRADE_OPEN   = 0xA0;
    static final int TRADE_UPDATE = 0xA1;
    static final int TRADE_CLOSE  = 0xA2;

    private TradePacketSender() {}

    public static void sendOpen(Player player, String partnerName, boolean isPlayerA) {
        send(player, PacketBuilder.create(TRADE_OPEN)
                .writeString(partnerName)
                .writeBoolean(isPlayerA)
                .build());
    }

    public static void sendUpdate(Player player, List<ItemStack> myOffer, List<ItemStack> partnerOffer,
                                  boolean myConfirmed, boolean partnerConfirmed,
                                  long myMoney, long partnerMoney,
                                  int  myPB,    int  partnerPB,
                                  ItemStack myCursor) {
        PacketBuilder packet = PacketBuilder.create(TRADE_UPDATE)
                .writeBoolean(myConfirmed)
                .writeBoolean(partnerConfirmed)
                .writeVarInt(myOffer.size());
        for (ItemStack item : myOffer) writeItem(packet, item);
        packet.writeVarInt(partnerOffer.size());
        for (ItemStack item : partnerOffer) writeItem(packet, item);
        packet.writeLong(myMoney).writeLong(partnerMoney);
        // PB + curseur ajoutés à la fin (lecture try/catch côté client)
        packet.writeVarInt(Math.max(0, myPB)).writeVarInt(Math.max(0, partnerPB));
        // Curseur propre au joueur (item « porté »). null = item vide (0xFFFF).
        writeItem(packet, myCursor);
        send(player, packet.build());
    }

    /** Overload sans curseur. */
    public static void sendUpdate(Player player, List<ItemStack> myOffer, List<ItemStack> partnerOffer,
                                  boolean myConfirmed, boolean partnerConfirmed,
                                  long myMoney, long partnerMoney,
                                  int  myPB,    int  partnerPB) {
        sendUpdate(player, myOffer, partnerOffer, myConfirmed, partnerConfirmed,
                myMoney, partnerMoney, myPB, partnerPB, null);
    }

    /** Overload pour appels existants (rétro-compatibilité du code serveur). */
    public static void sendUpdate(Player player, List<ItemStack> myOffer, List<ItemStack> partnerOffer,
                                  boolean myConfirmed, boolean partnerConfirmed,
                                  long myMoney, long partnerMoney) {
        sendUpdate(player, myOffer, partnerOffer, myConfirmed, partnerConfirmed,
                myMoney, partnerMoney, 0, 0, null);
    }

    public static void sendClose(Player player, boolean success) {
        send(player, PacketBuilder.create(TRADE_CLOSE)
                .writeBoolean(success)
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void send(Player player, byte[] payload) {
        if (payload == null || !player.isOnline()) return;
        player.sendPluginMessage(RedConflictCore.getInstance(), CHANNEL_S2C, payload);
    }

    /** Un objet : sa longueur, puis ses octets NMS bruts. */
    private static void writeItem(PacketBuilder packet, ItemStack item) {
        byte[] bytes = serializeItemNms(item);
        packet.writeVarInt(bytes.length).writeBytes(bytes);
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
}
