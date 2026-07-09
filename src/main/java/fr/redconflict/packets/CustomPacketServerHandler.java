package fr.redconflict.packets;

import fr.redconflict.RedConflictCore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class CustomPacketServerHandler implements PluginMessageListener {
    private final RedConflictCore plugin;

    public CustomPacketServerHandler(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            switch (packetId) {
                case 96:
                    handleCustomDrop(player, reader);
                    break;
            }
        } catch (Exception exception) {}
    }

    private void handleCustomDrop(Player player, PacketReader reader) throws IOException {
        ItemStack item = reader.readItemStackNms();
        int dropItemId = getNmsItemId(item);
        if (item == null || dropItemId <= 0 || item.getAmount() <= 0) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, player::updateInventory);
            return;
        }

        int dropAmount = item.getAmount();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            try {
                int found = 0;
                ItemStack[] contents = player.getInventory().getContents();
                for (ItemStack s : contents) {
                    if (s == null) continue;
                    int sid = getNmsItemId(s);
                    if (sid == dropItemId && s.getDurability() == item.getDurability()) {
                        found += s.getAmount();
                    }
                }

                if (found < dropAmount) {
                    player.updateInventory();
                    return;
                }

                removeItemsNms(player, dropItemId, item.getDurability(), dropAmount);

                try {
                    Location loc = player.getLocation();
                    ItemStack bukkitStack = item.clone();
                    Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".entity.CraftPlayer");
                    Object entityHuman = craftPlayerClass.getMethod("getHandle", new Class[0]).invoke(player, new Object[0]);
                    Class<?> entityHumanClass = entityHuman.getClass();
                    Class<?> worldClass = Class.forName("net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".World");
                    Class<?> itemStackNmsClass = Class.forName("net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".ItemStack");
                    Class<?> entityItemClass = Class.forName("net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".EntityItem");
                    Class<?> mathHelperClass = Class.forName("net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".MathHelper");
                    Class<?> craftItemStackClass = Class.forName("org.bukkit.craftbukkit." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".inventory.CraftItemStack");
                    Object nmsStack = craftItemStackClass.getMethod("asNMSCopy", new Class[] { ItemStack.class }).invoke(null, new Object[] { bukkitStack });
                    double d0 = loc.getY() - 0.3D + ((Float) entityHumanClass.getMethod("getHeadHeight", new Class[0]).invoke(entityHuman, new Object[0])).floatValue();
                    Object entityItem = entityItemClass.getConstructor(new Class[] { worldClass, double.class, double.class, double.class, itemStackNmsClass }).newInstance(new Object[] { entityHumanClass.getMethod("getWorld", new Class[0]).invoke(entityHuman, new Object[0]), loc.getX(), d0, loc.getZ(), nmsStack });
                    float yaw = ((Float) entityHumanClass.getField("yaw").get(entityHuman)).floatValue();
                    float pitch = ((Float) entityHumanClass.getField("pitch").get(entityHuman)).floatValue();
                    Random rand = new Random();
                    float f = 0.3F;
                    double motX = (-((Float) mathHelperClass.getMethod("sin", new Class[] { float.class }).invoke(null, new Object[] { yaw / 180.0F * 3.1415927F })).floatValue() * ((Float) mathHelperClass.getMethod("cos", new Class[] { float.class }).invoke(null, new Object[] { pitch / 180.0F * 3.1415927F })).floatValue() * f);
                    double motZ = (((Float) mathHelperClass.getMethod("cos", new Class[] { float.class }).invoke(null, new Object[] { yaw / 180.0F * 3.1415927F })).floatValue() * ((Float) mathHelperClass.getMethod("cos", new Class[] { float.class }).invoke(null, new Object[] { pitch / 180.0F * 3.1415927F })).floatValue() * f);
                    double motY = (-((Float) mathHelperClass.getMethod("sin", new Class[] { float.class }).invoke(null, new Object[] { pitch / 180.0F * 3.1415927F })).floatValue() * f + 0.1F);
                    float f1 = rand.nextFloat() * 3.1415927F * 2.0F;
                    float f2 = 0.02F * rand.nextFloat();
                    motX += Math.cos(f1) * f2;
                    motY += (rand.nextFloat() - rand.nextFloat()) * 0.1F;
                    motZ += Math.sin(f1) * f2;
                    entityItemClass.getField("motX").set(entityItem, motX);
                    entityItemClass.getField("motY").set(entityItem, motY);
                    entityItemClass.getField("motZ").set(entityItem, motZ);
                    entityItemClass.getMethod("a", new Class[] { int.class }).invoke(entityItem, new Object[] { 40 });
                    entityItemClass.getMethod("c", new Class[] { String.class }).invoke(entityItem, new Object[] { player.getName() });
                    worldClass.getMethod("addEntity", new Class[] { Class.forName("net.minecraft.server." + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".Entity") }).invoke(entityHumanClass.getMethod("getWorld", new Class[0]).invoke(entityHuman, new Object[0]), new Object[] { entityItem });
                } catch (Exception e) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                player.updateInventory();
            } catch (Exception e) {
                player.updateInventory();
            }
        });
    }

    public static int getNmsItemId(ItemStack item) {
        if (item == null) return 0;

        int typeId = item.getTypeId();
        if (typeId > 0) return typeId;

        // Fallback reflection NMS: asNMSCopy -> getItem -> Item.getId(item)
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
            Class<?> craftItemClass = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsItemStackClass = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> nmsItemClass = Class.forName("net.minecraft.server." + v + ".Item");
            Class<?> registryClass = Class.forName("net.minecraft.server." + v + ".RegistryMaterials");
            Class<?> minecraftKeyClass = Class.forName("net.minecraft.server." + v + ".MinecraftKey");

            Object nmsStack = craftItemClass.getMethod("asNMSCopy", new Class[] { ItemStack.class }).invoke(null, new Object[] { item });
            if (nmsStack == null) return 0;

            Object nmsItem = nmsItemStackClass.getMethod("getItem", new Class[0]).invoke(nmsStack, new Object[0]);
            if (nmsItem == null) return 0;

            int directId = ((Integer) nmsItemClass.getMethod("getId", new Class[] { nmsItemClass }).invoke(null, new Object[] { nmsItem })).intValue();
            if (directId > 0) return directId;

            // Si directId invalide, résout via clé registre -> Material enum
            Object registry = nmsItemClass.getField("REGISTRY").get(null);
            Iterable<?> keys = (Iterable<?>) registryClass.getMethod("keySet", new Class[0]).invoke(registry, new Object[0]);
            for (Object key : keys) {
                Object regItem = registryClass.getMethod("get", Object.class).invoke(registry, key);
                if (regItem != nmsItem) continue;
                String path = (String) minecraftKeyClass.getMethod("a", new Class[0]).invoke(key, new Object[0]);
                Material mat = Material.getMaterial(path.toUpperCase(Locale.ROOT));
                if (mat != null && mat != Material.AIR) {
                    return mat.getId();
                }
                break;
            }
        } catch (Exception ignored) {
        }

        return item.getType() != null ? item.getType().getId() : 0;
    }

    private void removeItemsNms(Player player, int nmsId, short damage, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s != null) {
                int sId = getNmsItemId(s);
                if (sId == nmsId && s.getDurability() == damage)
                    if (s.getAmount() <= remaining) {
                        remaining -= s.getAmount();
                        player.getInventory().setItem(i, null);
                    } else {
                        s.setAmount(s.getAmount() - remaining);
                        remaining = 0;
                    }
            }
        }
    }
}
