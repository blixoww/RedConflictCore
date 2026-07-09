package fr.originsfight.ring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les 8 slots ring par joueur.
 * Stockage : plugins/RedConflictCore/rings/<uuid>.json
 */
public class RingManager {

    public static final int RING_SIZE = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final JsonParser PARSER = new JsonParser();

    private final JavaPlugin plugin;
    private final File ringDir;

    /** Cache en mémoire : UUID → tableau de 8 ItemStack (null = slot vide) */
    private final Map<UUID, ItemStack[]> cache = new HashMap<>();

    public RingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ringDir = new File(plugin.getDataFolder(), "rings");
        if (!ringDir.exists()) ringDir.mkdirs();
    }

    // ── Chargement / sauvegarde ──────────────────────────────────────────────

    public void loadPlayer(UUID uuid) {
        File file = fileFor(uuid);
        ItemStack[] slots = new ItemStack[RING_SIZE];
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject obj = PARSER.parse(reader).getAsJsonObject();
                JsonArray arr = obj.getAsJsonArray("slots");
                for (int i = 0; i < RING_SIZE && i < arr.size(); i++) {
                    JsonElement el = arr.get(i);
                    if (!el.isJsonNull()) {
                        try {
                            slots[i] = deserializeItem(el.getAsString());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[RingManager] Impossible de charger " + uuid + ": " + e.getMessage());
            }
        }
        cache.put(uuid, slots);
    }

    public void savePlayer(UUID uuid) {
        ItemStack[] slots = cache.get(uuid);
        if (slots == null) return;
        JsonObject obj = new JsonObject();
        JsonArray arr = new JsonArray();
        for (ItemStack stack : slots) {
            if (stack == null) {
                arr.add(JsonNull.INSTANCE);
            } else {
                try {
                    arr.add(GSON.toJsonTree(serializeItem(stack)));
                } catch (Exception e) {
                    arr.add(JsonNull.INSTANCE);
                }
            }
        }
        obj.add("slots", arr);
        try (FileWriter writer = new FileWriter(fileFor(uuid))) {
            GSON.toJson(obj, writer);
        } catch (IOException e) {
            plugin.getLogger().warning("[RingManager] Impossible de sauvegarder " + uuid + ": " + e.getMessage());
        }
    }

    public void unloadPlayer(UUID uuid) {
        savePlayer(uuid);
        cache.remove(uuid);
    }

    // ── API publique ─────────────────────────────────────────────────────────

    /** Retourne les 8 slots ring (peut contenir des null). Crée le tableau si absent. */
    public ItemStack[] getSlots(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> new ItemStack[RING_SIZE]);
    }

    public ItemStack getSlot(UUID uuid, int index) {
        return getSlots(uuid)[index];
    }

    public void setSlot(UUID uuid, int index, ItemStack item) {
        getSlots(uuid)[index] = item;
    }

    public void clearSlot(UUID uuid, int index) {
        getSlots(uuid)[index] = null;
    }

    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    // ── Sauvegarde périodique ────────────────────────────────────────────────

    public void startAutoSave(int periodTicks) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID uuid : cache.keySet()) {
                savePlayer(uuid);
            }
        }, periodTicks, periodTicks);
    }

    // ── Sérialisation ItemStack (via Base64 NMS) ─────────────────────────────

    private String serializeItem(ItemStack item) throws Exception {
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> craftStackClass = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
        Class<?> nmsStackClass   = Class.forName("net.minecraft.server." + v + ".ItemStack");
        Class<?> nbtCompClass    = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
        Object nmsStack = craftStackClass.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
        Object nbt = nbtCompClass.getDeclaredConstructor().newInstance();
        nmsStack.getClass().getMethod("save", nbtCompClass).invoke(nmsStack, nbt);
        return nbt.toString();
    }

    private ItemStack deserializeItem(String nbtStr) throws Exception {
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> craftStackClass = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
        Class<?> nmsStackClass   = Class.forName("net.minecraft.server." + v + ".ItemStack");
        Class<?> nbtParserClass  = Class.forName("net.minecraft.server." + v + ".MojangsonParser");
        Class<?> nbtCompClass    = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
        Object nbt = nbtParserClass.getMethod("parse", String.class).invoke(null, nbtStr);
        Object nmsStack = nmsStackClass.getMethod("createStack", nbtCompClass).invoke(null, nbt);
        return (ItemStack) craftStackClass.getMethod("asBukkitCopy", nmsStackClass).invoke(null, nmsStack);
    }

    private File fileFor(UUID uuid) {
        return new File(ringDir, uuid.toString() + ".json");
    }
}
