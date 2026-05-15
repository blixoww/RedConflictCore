package fr.originsfight.boutique;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * Envoie au client modifié la snapshot complète de la boutique PB.
 * Pendant : {@link BoutiqueClientServerHandler} pour la réception.
 *
 * Canal : CUSTOM:BOUTIQUE_S2C
 * Packets : BOUTIQUE_DATA (0xB0), BOUTIQUE_RESULT (0xB1)
 */
public final class BoutiquePacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:BOUTIQUE_S2C";

    public static final int BOUTIQUE_DATA   = 0xB0;
    public static final int BOUTIQUE_RESULT = 0xB1;

    private BoutiquePacketSender() {}

    public static void sendData(Player player) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        PacketBuilder pb = PacketBuilder.create(BOUTIQUE_DATA);

        pb.writeString(plugin.getBoutiqueConfig().getString("boutique.titre", "&c&lBoutique RedConflict"));

        long money = 0L;
        try { if (plugin.getEconomy() != null) money = (long) plugin.getEconomy().getBalance(player); }
        catch (Exception ignored) {}
        int playerPB = plugin.getPBManager() != null ? plugin.getPBManager().get(player) : 0;
        pb.writeLong(money);
        pb.writeVarInt(playerPB);

        writeCategory(pb, plugin.getBoutiqueConfig().getList("boutique.grades"),    "commandes");
        writeCategory(pb, plugin.getBoutiqueConfig().getList("boutique.kits"),      "commande");
        writeCategory(pb, plugin.getBoutiqueConfig().getList("boutique.commandes"), "commande");
        writeCategory(pb, plugin.getBoutiqueConfig().getList("boutique.spawners"),  "commande");

        OffreSpeciale of = plugin.getOffresManager() != null ? plugin.getOffresManager().getCurrent() : null;
        pb.writeBoolean(of != null);
        if (of != null) {
            pb.writeString(of.id);
            pb.writeBytes(serializeItem(of.buildPurchasable()));
            pb.writeString(of.nom);
            pb.writeVarInt(of.lore.size());
            for (String l : of.lore) pb.writeString(l);
            pb.writeLong(of.prixMonnaie);
            pb.writeVarInt(of.prixPB);
            pb.writeVarInt(of.stock);
            pb.writeVarInt(of.stockInitial);
            pb.writeLong(of.expiresAt);
        }

        // Packs — extension en fin de paquet (lue via try/catch côté client pour compat.)
        writeCategory(pb, plugin.getBoutiqueConfig().getList("boutique.packs"), "commandes");

        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
    }

    public static void sendResult(Player player, boolean success, String message) {
        byte[] data = PacketBuilder.create(BOUTIQUE_RESULT)
                .writeBoolean(success)
                .writeString(truncate(message, 200))
                .build();
        player.sendPluginMessage((Plugin) OriginsFightCore.getInstance(), CHANNEL_S2C, data);
    }

    // ── Sérialisation catégorie ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void writeCategory(PacketBuilder pb, List<?> entries, String cmdField) {
        if (entries == null) { pb.writeVarInt(0); return; }
        pb.writeVarInt(entries.size());
        for (Object o : entries) {
            if (!(o instanceof Map)) {
                // entrée vide pour rester aligné
                pb.writeString("");
                pb.writeString("");
                pb.writeString("STONE");
                pb.writeVarInt(0);
                pb.writeLong(0);
                pb.writeVarInt(0);
                pb.writeVarInt(0);
                pb.writeString("");
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String id = String.valueOf(m.getOrDefault("id", ""));
            String nom = String.valueOf(m.getOrDefault("nom", ""));
            String icone = m.get("icone") != null ? String.valueOf(m.get("icone")) : "PAPER";
            int prixPB = asInt(m.get("prix_pb"));
            long prixM = asLong(m.get("prix_monnaie"));
            int duree = asInt(m.get("duree"));
            String mob = m.get("mob") != null ? String.valueOf(m.get("mob")) : "";

            pb.writeString(id);
            pb.writeString(nom);
            pb.writeString(icone);

            List<String> desc = (m.get("description") instanceof List)
                    ? toStringList((List<?>) m.get("description")) : java.util.Collections.emptyList();
            pb.writeVarInt(desc.size());
            for (String d : desc) pb.writeString(d);

            pb.writeLong(prixM);
            pb.writeVarInt(prixPB);
            pb.writeVarInt(duree);
            pb.writeString(mob);
            // Prix permanents (0 si non défini → pas d'option perm pour cet article)
            long prixMPerm = asLong(m.get("prix_monnaie_perm"));
            int  prixPBPerm = asInt(m.get("prix_pb_perm"));
            pb.writeLong(prixMPerm);
            pb.writeVarInt(prixPBPerm);
        }
    }

    private static byte[] serializeItem(ItemStack item) {
        if (item == null) return new byte[] {-1, -1};
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craft = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsIs = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> unp = Class.forName("io.netty.buffer.Unpooled");
            Class<?> bb = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> pds = Class.forName("net.minecraft.server." + v + ".PacketDataSerializer");
            Object nms = craft.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            Object buf = unp.getMethod("buffer").invoke(null);
            Object ser = pds.getConstructor(bb).newInstance(buf);
            pds.getMethod("a", nmsIs).invoke(ser, nms);
            int len = (int) bb.getMethod("readableBytes").invoke(buf);
            byte[] result = new byte[len];
            bb.getMethod("getBytes", int.class, byte[].class).invoke(buf, 0, result);
            return result;
        } catch (Exception e) {
            return new byte[] {-1, -1};
        }
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) try { return Integer.parseInt((String) o); } catch (Exception ignored) {}
        return 0;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) try { return Long.parseLong((String) o); } catch (Exception ignored) {}
        return 0L;
    }

    private static List<String> toStringList(List<?> raw) {
        java.util.List<String> out = new java.util.ArrayList<>(raw.size());
        for (Object o : raw) out.add(String.valueOf(o));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
