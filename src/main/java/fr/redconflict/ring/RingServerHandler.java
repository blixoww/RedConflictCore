package fr.redconflict.ring;

import fr.redconflict.packets.PacketReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reçoit les paquets ring C2S depuis le client (modèle serveur-autoritaire).
 *
 * C2S 0xD0 RING_SYNC_REQUEST : répond avec un snapshot complet.
 * C2S 0xD2 RING_SLOT_CLICK   : byte slotIndex | byte button | byte shift
 */
public class RingServerHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:RING_C2S";

    private static final int RING_SYNC_REQUEST = 0xD0;
    private static final int RING_SLOT_CLICK   = 0xD2;

    private final RingManager     manager;
    private final RingPacketSender sender;

    public RingServerHandler(RingManager manager, RingPacketSender sender) {
        this.manager = manager;
        this.sender  = sender;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            switch (packetId) {
                case RING_SYNC_REQUEST:
                    sender.sendSync(player);
                    break;
                case RING_SLOT_CLICK:
                    handleSlotClick(player, reader);
                    break;
            }
        } catch (Exception ignored) {}
    }

    // ── Logique de clic (serveur-autoritaire) ────────────────────────────────

    private void handleSlotClick(Player player, PacketReader reader) throws java.io.IOException {
        int slotIndex = reader.readByte() & 0xFF;
        reader.readByte();                       // button : non utilisé
        int shift = reader.readByte() & 0xFF;
        if (slotIndex >= RingManager.RING_SIZE) return;

        UUID uuid = player.getUniqueId();
        ItemStack cursor = normalize(player.getItemOnCursor());
        ItemStack ring   = normalize(manager.getSlot(uuid, slotIndex));

        ItemStack newCursor;
        ItemStack newRing;

        if (shift == 1) {
            // Shift-clic : déséquiper l'anneau vers l'inventaire.
            if (ring == null) return;
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(ring);
            if (!leftover.isEmpty()) return;
            manager.setSlot(uuid, slotIndex, null);
            player.updateInventory();
            sender.sendSync(player);
            return;
        } else if (cursor == null) {
            // Curseur vide → prendre l'anneau du slot.
            if (ring == null) return;
            newCursor = ring;
            newRing   = null;
        } else {
            // Curseur tient un item → seul un anneau valide peut entrer dans un slot ring.
            if (!isRingItem(cursor)) return;
            // Si l'item est bien un ItemRing mais sans les tags NBT, on les ajoute pour que
            // le système d'effets fonctionne correctement.
            ensureRingTags(cursor);
            newRing   = cursor.clone();
            newCursor = ring;
        }

        manager.setSlot(uuid, slotIndex, newRing);

        // 1) Mettre à jour l'état serveur du curseur.
        player.setItemOnCursor(newCursor != null ? newCursor : new ItemStack(Material.AIR));

        // 2) Forcer la mise à jour visuelle du curseur côté client via NMS
        //    (player.setItemOnCursor ne garantit pas l'envoi du paquet en 1.8.9).
        sendSetSlotCursor(player, newCursor);

        sender.sendSync(player);
    }

    // ── Mise à jour du curseur client ────────────────────────────────────────

    /** Envoie PacketPlayOutSetSlot(-1, -1, item) pour mettre à jour le curseur client. */
    private static void sendSetSlotCursor(Player player, ItemStack item) {
        try {
            String v = nmsVersion();
            Class<?> craftStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsStackCls   = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> packetCls     = Class.forName("net.minecraft.server." + v + ".PacketPlayOutSetSlot");
            Class<?> packetBaseCls = Class.forName("net.minecraft.server." + v + ".Packet");

            Object nmsItem  = craftStackCls.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            Object handle   = player.getClass().getMethod("getHandle").invoke(player);
            Object conn     = handle.getClass().getField("playerConnection").get(handle);
            Constructor<?> ctor = packetCls.getConstructor(int.class, int.class, nmsStackCls);
            Object packet   = ctor.newInstance(-1, -1, nmsItem);
            conn.getClass().getMethod("sendPacket", packetBaseCls).invoke(conn, packet);
        } catch (Exception e) {
            // Non-fatal : la prochaine interaction ou le sync S2C corrigera l'état.
            Bukkit.getLogger().warning("[RingServerHandler] sendSetSlotCursor: " + e.getMessage());
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private static String nmsVersion() {
        return Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
    }

    /**
     * Vérifie qu'un ItemStack est un ring item valide.
     *
     * Deux cas acceptés :
     *  1) L'item est une instance de {@code net.minecraft.server.<v>.ItemRing}
     *     (items craftés ET items donnés via /give ou créatif).
     *  2) L'item possède le NBT {@code is_ring_item:1b} (fallback pour items
     *     créés par d'autres moyens).
     */
    private boolean isRingItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        try {
            String v = nmsVersion();
            Class<?> craftStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsStackCls   = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Object nmsStack = craftStackCls.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);

            // ── Vérification 1 : instanceof ItemRing ─────────────────────────
            try {
                // ItemRing est une classe custom dans net.minecraft.server.<v>
                Class<?> itemRingCls = Class.forName("net.minecraft.server." + v + ".ItemRing");
                Object nmsItemType   = nmsStackCls.getMethod("getItem").invoke(nmsStack);
                if (itemRingCls.isInstance(nmsItemType)) return true;
            } catch (ClassNotFoundException ignored) {
                // Au cas où le class-remap est différent : on tente sans version
                try {
                    Class<?> itemRingCls = Class.forName("net.minecraft.server.ItemRing");
                    Object nmsItemType   = nmsStackCls.getMethod("getItem").invoke(nmsStack);
                    if (itemRingCls.isInstance(nmsItemType)) return true;
                } catch (Exception ignored2) {}
            }

            // ── Vérification 2 : NBT is_ring_item ────────────────────────────
            Class<?> nbtCompCls = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
            Method hasTag  = nmsStackCls.getMethod("hasTag");
            if (!(boolean) hasTag.invoke(nmsStack)) return false;
            Object tag     = nmsStackCls.getMethod("getTag").invoke(nmsStack);
            if (tag == null) return false;
            Method hasKey  = nbtCompCls.getMethod("hasKey", String.class);
            if (!(boolean) hasKey.invoke(tag, "is_ring_item")) return false;
            return (byte) nbtCompCls.getMethod("getByte", String.class).invoke(tag, "is_ring_item") == 1;

        } catch (Exception e) {
            Bukkit.getLogger().warning("[RingServerHandler] isRingItem error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Si l'item est un ItemRing sans tags NBT (cas /give / créatif), on ajoute
     * {@code is_ring_item:1b} et {@code ring_item_id} pour que le système d'effets
     * fonctionne correctement.
     */
    private void ensureRingTags(ItemStack item) {
        try {
            String v = nmsVersion();
            Class<?> craftStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsStackCls   = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> nbtCompCls    = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
            Class<?> itemRingCls;
            try {
                itemRingCls = Class.forName("net.minecraft.server." + v + ".ItemRing");
            } catch (ClassNotFoundException e) {
                itemRingCls = Class.forName("net.minecraft.server.ItemRing");
            }

            Object nmsStack    = craftStackCls.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            Object nmsItemType = nmsStackCls.getMethod("getItem").invoke(nmsStack);
            if (!itemRingCls.isInstance(nmsItemType)) return;

            // Vérifier si les tags sont déjà présents
            boolean hasTag = (boolean) nmsStackCls.getMethod("hasTag").invoke(nmsStack);
            Object tag;
            if (!hasTag) {
                tag = nbtCompCls.getDeclaredConstructor().newInstance();
                nmsStackCls.getMethod("setTag", nbtCompCls).invoke(nmsStack, tag);
            } else {
                tag = nmsStackCls.getMethod("getTag").invoke(nmsStack);
            }

            boolean hasRingId = (boolean) nbtCompCls.getMethod("hasKey", String.class).invoke(tag, "ring_item_id");
            if (!hasRingId) {
                // Récupérer le ringItemId depuis le ItemRing NMS
                String ringItemId = (String) itemRingCls.getMethod("getRingItemId").invoke(nmsItemType);
                nbtCompCls.getMethod("setByte", String.class, byte.class).invoke(tag, "is_ring_item", (byte) 1);
                nbtCompCls.getMethod("setString", String.class, String.class).invoke(tag, "ring_item_id", ringItemId);
                // Re-sérialiser dans le Bukkit ItemStack
                ItemStack tagged = (ItemStack) craftStackCls.getMethod(
                        "asBukkitCopy", nmsStackCls).invoke(null, nmsStack);
                // Copier les métadonnées dans l'item original
                item.setItemMeta(tagged.getItemMeta());
            }
        } catch (Exception ignored) {}
    }

    private static ItemStack normalize(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item;
    }
}
