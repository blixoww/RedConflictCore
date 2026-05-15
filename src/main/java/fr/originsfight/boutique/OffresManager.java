package fr.originsfight.boutique;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Gère les offres spéciales (édition limitée) :
 * - définitions chargées depuis config.yml
 * - une seule offre active à la fois
 * - timer + stock, reroll auto à minuit / expiration / rupture
 * - probabilité d'absence (config: offres_speciales.chance_apparition)
 */
public class OffresManager {

    private final OriginsFightCore plugin;
    private final Map<String, OffreSpeciale> definitions = new LinkedHashMap<>();
    private OffreSpeciale current;
    private BukkitTask tickTask;
    private final Random random = new Random();
    private long lastDailyRoll = 0L;

    public OffresManager(OriginsFightCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        definitions.clear();
        List<?> list = plugin.getBoutiqueConfig().getList("offres_speciales.offres");
        if (list == null) return;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            OffreSpeciale def = OffreSpeciale.fromMap((Map<?, ?>) o);
            if (def.id != null && !def.id.isEmpty()) definitions.put(def.id, def);
        }
    }

    public void start() {
        rollNew(true);
        int interval = plugin.getBoutiqueConfig().getInt("offres_speciales.intervalle_check_seconds", 60);
        if (interval < 5) interval = 5;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L * interval);
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
    }

    private void tick() {
        if (isDayChanged()) {
            rollNew(true);
            return;
        }
        if (current == null) return;
        if (System.currentTimeMillis() >= current.expiresAt || current.stock <= 0) rollNew(false);
    }

    private boolean isDayChanged() {
        Calendar c = Calendar.getInstance();
        long today = c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
        if (lastDailyRoll != today && c.get(Calendar.HOUR_OF_DAY) == 0) {
            lastDailyRoll = today;
            return true;
        }
        return false;
    }

    public void rollNew(boolean respectChance) {
        if (respectChance) {
            double chance = plugin.getBoutiqueConfig().getDouble("offres_speciales.chance_apparition", 0.7);
            if (random.nextDouble() > chance) {
                current = null;
                return;
            }
        }
        List<OffreSpeciale> pool = new ArrayList<>();
        for (OffreSpeciale def : definitions.values()) if (def.actif) pool.add(def);
        if (pool.isEmpty()) { current = null; return; }
        OffreSpeciale picked = pool.get(random.nextInt(pool.size()));
        current = picked.newInstance();
    }

    public OffreSpeciale getCurrent() {
        if (current != null && current.stock > 0 && System.currentTimeMillis() < current.expiresAt)
            return current;
        return null;
    }

    public void consumeStock() {
        if (current != null) {
            current.stock = Math.max(0, current.stock - 1);
            if (current.stock <= 0) rollNew(false);
        }
    }

    public boolean setEnabled(String id, boolean enabled) {
        OffreSpeciale def = definitions.get(id);
        if (def == null) return false;
        def.actif = enabled;
        if (!enabled && current != null && id.equals(current.id)) rollNew(false);
        return true;
    }

    public void rerollNow() { rollNew(false); }

    public Collection<String> listIds() { return definitions.keySet(); }
}
