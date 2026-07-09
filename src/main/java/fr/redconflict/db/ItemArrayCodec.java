package fr.redconflict.db;

import fr.redconflict.hdv.HdvDatabase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.logging.Logger;

/**
 * Sérialise/désérialise un tableau d'{@link ItemStack} en un seul tableau d'octets.
 *
 * <p>Réutilise la sérialisation NMS NBT de {@link HdvDatabase#serializeItemStatic(ItemStack)} /
 * {@link HdvDatabase#deserializeItem(byte[])}, qui préserve fidèlement les items moddés custom
 * (anneaux, items MCP dont {@code getTypeId()} peut être 0) — contrairement à la sérialisation Bukkit.
 *
 * <p>Format : {@code int nbSlots} puis pour chaque slot {@code int len} (0 = slot vide) suivi des
 * {@code len} octets de l'item.
 */
public final class ItemArrayCodec {

    private static final Logger LOG = Logger.getLogger("PlayerDataSync");

    private ItemArrayCodec() {}

    public static byte[] encode(ItemStack[] items) {
        if (items == null) items = new ItemStack[0];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(items.length);
            for (ItemStack it : items) {
                if (it == null || it.getType() == Material.AIR) { out.writeInt(0); continue; }
                byte[] b = HdvDatabase.serializeItemStatic(it);
                if (b == null || b.length == 0) { out.writeInt(0); continue; }
                out.writeInt(b.length);
                out.write(b);
            }
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            LOG.warning("[Sync] encode error: " + e.getMessage());
            return new byte[0];
        }
    }

    /** Décode un tableau d'items. Retourne {@code null} si {@code data} est vide/invalide. */
    public static ItemStack[] decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int n = in.readInt();
            if (n < 0 || n > 1024) return null; // garde-fou
            ItemStack[] arr = new ItemStack[n];
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                if (len <= 0) { arr[i] = null; continue; }
                byte[] b = new byte[len];
                in.readFully(b);
                arr[i] = HdvDatabase.deserializeItem(b);
            }
            return arr;
        } catch (Exception e) {
            LOG.warning("[Sync] decode error: " + e.getMessage());
            return null;
        }
    }
}
