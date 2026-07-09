package fr.redconflict.ring;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Accès aux anneaux équipés pour l'application des effets.
 *
 * Singleton statique initialisé au démarrage du plugin afin que les listeners
 * et le ShopManager puissent interroger les anneaux sans injection de dépendance.
 */
public final class RingEffects {

    // Identifiants des anneaux (doivent correspondre à ring_item_id).
    public static final String MAGNET               = "MAGNET";
    public static final String NECKLACE_OF_SATURATION = "NECKLACE_OF_SATURATION";
    public static final String RING_OF_EXPERIENCE   = "RING_OF_EXPERIENCE";
    public static final String NECKLACE_OF_MERCHANT = "NECKLACE_OF_MERCHANT";
    public static final String RING_OF_FORTUNE      = "RING_OF_FORTUNE";
    public static final String NECKLACE_OF_FALL     = "NECKLACE_OF_FALL";
    public static final String RING_OF_HASTE        = "RING_OF_HASTE";
    public static final String TOTEM_OF_UNDYING     = "TOTEM_OF_UNDYING";

    private static RingManager manager;

    private RingEffects() {}

    public static void init(RingManager ringManager) {
        manager = ringManager;
    }

    /** Retourne le RingManager (peut être null si pas encore initialisé). */
    public static RingManager getManager() {
        return manager;
    }

    /** True si le joueur a l'anneau {@code ringItemId} équipé dans un de ses 8 slots. */
    public static boolean hasRing(Player player, String ringItemId) {
        if (manager == null || player == null) return false;
        ItemStack[] slots = manager.getSlots(player.getUniqueId());
        if (slots == null) return false;
        for (ItemStack stack : slots) {
            if (stack == null) continue;
            if (ringItemId.equals(readRingId(stack))) return true;
        }
        return false;
    }

    /** Lit le NBT ring_item_id d'un ItemStack Bukkit via réflexion NMS. */
    private static String readRingId(ItemStack item) {
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftStack = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsStack   = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> nbtComp    = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
            Object nms = craftStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            Object tag = nmsStack.getMethod("getTag").invoke(nms);
            if (tag == null) return null;
            Object hasKey = nbtComp.getMethod("hasKey", String.class).invoke(tag, "ring_item_id");
            if (!(Boolean) hasKey) return null;
            return (String) nbtComp.getMethod("getString", String.class).invoke(tag, "ring_item_id");
        } catch (Exception e) {
            return null;
        }
    }
}
