package fr.redconflict.shop;

import fr.redconflict.RedConflictCore;
import fr.redconflict.shop.ShopDatabase.ShopEventRow;
import fr.redconflict.shop.ShopDatabase.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Gestionnaire d'événements boursiers — krach, inflation, aubaines.
 *
 * Les événements sont une couche multiplicative au-dessus du prix naturel :
 *   priceAffiche = currentPrice * (produit des multiplicateurs des events actifs)
 *
 * Les events n'écrivent jamais dans shop_price_history (=> les courbes 7j
 * restent propres et reflètent la régression naturelle).
 *
 * Tâche scheduler : tick toutes les 60 secondes pour :
 *   - expirer les events terminés (annonce + push)
 *   - tenter un roll quotidien si on a passé minuit
 */
public class ShopEventManager {

    private static final Logger LOG = Logger.getLogger("Shop-Event");

    public static final String TYPE_KRACH     = "KRACH";
    public static final String TYPE_INFLATION = "INFLATION";
    public static final String TYPE_AUBAINE   = "AUBAINE";

    private static ShopEventManager instance;
    public static ShopEventManager getInstance() { return instance; }

    private final RedConflictCore plugin;
    private final ShopManager shopManager;
    private final ShopDatabase database;
    private final Random rng = new Random();

    // Cache des events actifs (rechargé après toute mutation)
    private volatile List<ShopEventRow> activeCache = Collections.emptyList();

    // Config
    private final EventConfig cKrach     = new EventConfig();
    private final EventConfig cInflation = new EventConfig();
    private final AubaineConfig cAubaine = new AubaineConfig();
    private boolean announceBroadcast = true;
    private boolean announceTitle     = true;
    private boolean notifyOnJoin      = true;
    private int maxConcurrentGlobal   = 1;
    private int maxConcurrentAubaines = 5;

    // Date du dernier roll quotidien (yyyymmdd)
    private int lastDailyRollDay = -1;

    private int tickTaskId = -1;

    public ShopEventManager(RedConflictCore plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.database = shopManager.getDatabase();
        instance = this;
    }

    public void enable() {
        loadConfig();
        database.purgeOldEvents();
        refreshCache();
        // Tick 60s
        tickTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 30L, 20L * 60L).getTaskId();
        LOG.info("[ShopEvent] EventManager initialisé. " + activeCache.size() + " event(s) actif(s).");
    }

    public void disable() {
        if (tickTaskId != -1) Bukkit.getScheduler().cancelTask(tickTaskId);
    }

    public void reload() {
        loadConfig();
        LOG.info("[ShopEvent] Config rechargée.");
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private void loadConfig() {
        try {
            File f = new File(plugin.getDataFolder(), "shop/shop_events.yml");
            if (!f.exists()) plugin.saveResource("shop/shop_events.yml", false);
            org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                cfg.load(r);
            }
            cKrach.load(cfg.getConfigurationSection("krach"));
            cInflation.load(cfg.getConfigurationSection("inflation"));
            cAubaine.load(cfg.getConfigurationSection("aubaine"));
            announceBroadcast     = cfg.getBoolean("announce.prefix_broadcast", true);
            announceTitle         = cfg.getBoolean("announce.send_title", true);
            notifyOnJoin          = cfg.getBoolean("announce.notify_on_join", true);
            maxConcurrentGlobal   = cfg.getInt("max_concurrent_global", 1);
            maxConcurrentAubaines = cfg.getInt("max_concurrent_aubaines", 5);
        } catch (Exception e) {
            LOG.severe("[ShopEvent] Erreur chargement shop_events.yml: " + e.getMessage());
        }
    }

    public boolean isNotifyOnJoin() { return notifyOnJoin; }

    // ── Tick principal ────────────────────────────────────────────────────────

    private void tick() {
        // Expirer les events finis
        List<ShopEventRow> previous = activeCache;
        List<ShopEventRow> current  = database.getActiveEvents();
        Set<Long> currentIds = new HashSet<>();
        for (ShopEventRow e : current) currentIds.add(e.id);
        boolean anyExpired = false;
        for (ShopEventRow e : previous) {
            if (!currentIds.contains(e.id)) {
                announceEnd(e);
                anyExpired = true;
            }
        }
        activeCache = current;

        // Roll quotidien (basé sur le jour de l'année courant)
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        if (lastDailyRollDay != today) {
            lastDailyRollDay = today;
            attemptDailyRoll();
        }

        // Push aux clients : seulement si quelque chose a changé.
        // Le client peut décrémenter le timer localement à partir du dernier état reçu.
        if (anyExpired) {
            broadcastEventState();
        }
    }

    private void attemptDailyRoll() {
        boolean hasGlobal = countActiveGlobals() > 0;
        if (!hasGlobal && rollPercent(cKrach.dailyChance) && cKrach.enabled) {
            launchKrach(-1, false);
        } else if (!hasGlobal && rollPercent(cInflation.dailyChance) && cInflation.enabled) {
            launchInflation(-1, false);
        }
        if (cAubaine.enabled && countActiveAubaines() < maxConcurrentAubaines && rollPercent(cAubaine.dailyChance)) {
            launchRandomAubaine(-1, false);
        }
    }

    // ── Lancement des events ──────────────────────────────────────────────────

    /** durationMinutes ≤ 0 → random selon config. Returns event id ou -1. */
    public long launchKrach(int durationMinutes, boolean manual) {
        if (!cKrach.enabled && !manual) return -1L;
        if (countActiveGlobals() >= maxConcurrentGlobal) return -1L;
        int dur  = durationMinutes > 0 ? durationMinutes : randInt(cKrach.durationMin, cKrach.durationMax);
        double mb = randDouble(cKrach.multBuyMin,  cKrach.multBuyMax);
        double ms = randDouble(cKrach.multSellMin, cKrach.multSellMax);
        long now = System.currentTimeMillis() / 1000L;
        long end = now + dur * 60L;
        String ann = cKrach.announceStart.replace("{duration}", formatDuration(dur));
        long id = database.insertEvent(TYPE_KRACH, now, end, mb, ms, "", manual, ann);
        if (id == -1L) return -1L;
        refreshCache();
        announceStart(ann, TYPE_KRACH);
        broadcastEventState();
        LOG.info("[ShopEvent] KRACH lancé #" + id + " (durée=" + dur + "min, mb=" + mb + ", ms=" + ms + ")");
        return id;
    }

    public long launchInflation(int durationMinutes, boolean manual) {
        if (!cInflation.enabled && !manual) return -1L;
        if (countActiveGlobals() >= maxConcurrentGlobal) return -1L;
        int dur  = durationMinutes > 0 ? durationMinutes : randInt(cInflation.durationMin, cInflation.durationMax);
        double mb = randDouble(cInflation.multBuyMin,  cInflation.multBuyMax);
        double ms = randDouble(cInflation.multSellMin, cInflation.multSellMax);
        long now = System.currentTimeMillis() / 1000L;
        long end = now + dur * 60L;
        String ann = cInflation.announceStart.replace("{duration}", formatDuration(dur));
        long id = database.insertEvent(TYPE_INFLATION, now, end, mb, ms, "", manual, ann);
        if (id == -1L) return -1L;
        refreshCache();
        announceStart(ann, TYPE_INFLATION);
        broadcastEventState();
        LOG.info("[ShopEvent] INFLATION lancée #" + id + " (durée=" + dur + "min, mb=" + mb + ", ms=" + ms + ")");
        return id;
    }

    /** Lance une aubaine sur N items pondérés par activité (volume 7j). */
    public long launchRandomAubaine(int durationMinutes, boolean manual) {
        if (!cAubaine.enabled && !manual) return -1L;
        int nbItems = randInt(cAubaine.itemsMin, cAubaine.itemsMax);
        List<Integer> picked = pickWeightedItems(nbItems);
        if (picked.isEmpty()) return -1L;
        boolean upward = rollPercent(cAubaine.upwardChance);
        double magnitude;
        double mb, ms;
        if (upward) {
            magnitude = randDouble(cAubaine.upMultMin, cAubaine.upMultMax);
            mb = 1.0; ms = magnitude;
        } else {
            magnitude = randDouble(cAubaine.downMultMin, cAubaine.downMultMax);
            mb = magnitude; ms = 1.0;
        }
        return launchAubaine(picked, upward, mb, ms, durationMinutes, manual);
    }

    /** Lance une aubaine explicite sur des items donnés. */
    public long launchAubaine(List<Integer> itemIds, boolean upward,
                               double multBuy, double multSell,
                               int durationMinutes, boolean manual) {
        if (itemIds == null || itemIds.isEmpty()) return -1L;
        if (countActiveAubaines() >= maxConcurrentAubaines && !manual) return -1L;
        int dur = durationMinutes > 0 ? durationMinutes : randInt(cAubaine.durationMin, cAubaine.durationMax);
        long now = System.currentTimeMillis() / 1000L;
        long end = now + dur * 60L;

        StringBuilder csv = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (int idx = 0; idx < itemIds.size(); idx++) {
            int iid = itemIds.get(idx);
            if (idx > 0) csv.append(',');
            csv.append(iid);
            ShopItem si = database.getItemById(iid);
            if (si != null) {
                if (names.length() > 0) names.append(", ");
                names.append(si.displayName);
            }
        }
        String tmpl = upward ? cAubaine.announceStartUp : cAubaine.announceStartDown;
        String ann  = tmpl.replace("{items}", names.toString())
                          .replace("{duration}", formatDuration(dur));
        long id = database.insertEvent(TYPE_AUBAINE, now, end, multBuy, multSell, csv.toString(), manual, ann);
        if (id == -1L) return -1L;
        refreshCache();
        announceStart(ann, TYPE_AUBAINE);
        broadcastEventState();
        LOG.info("[ShopEvent] AUBAINE lancée #" + id + " (items=" + csv + ", upward=" + upward
                 + ", mb=" + multBuy + ", ms=" + multSell + ", durée=" + dur + "min)");
        return id;
    }

    public boolean stopEvent(long id) {
        ShopEventRow e = database.getEventById(id);
        if (e == null) return false;
        long now = System.currentTimeMillis() / 1000L;
        if (e.endTs <= now) return false;
        database.terminateEvent(id);
        refreshCache();
        announceEnd(e);
        broadcastEventState();
        return true;
    }

    public int stopAll() {
        int n = 0;
        for (ShopEventRow e : new ArrayList<>(activeCache)) {
            database.terminateEvent(e.id);
            announceEnd(e);
            n++;
        }
        refreshCache();
        broadcastEventState();
        return n;
    }

    public int stopByType(String type) {
        int n = 0;
        for (ShopEventRow e : new ArrayList<>(activeCache)) {
            if (type.equals(e.type)) {
                database.terminateEvent(e.id);
                announceEnd(e);
                n++;
            }
        }
        if (n > 0) {
            refreshCache();
            broadcastEventState();
        }
        return n;
    }

    /**
     * Déclenché par /shopdebug tick all : simule un passage de 24h pour les events.
     * Expire les events qui auraient dû finir, puis tente un roll quotidien.
     * À appeler sur le thread principal après la régression des prix.
     */
    public void tickForSimulation() {
        // Avancer les timestamps de 24h dans la DB, puis rafraîchir le cache
        database.advanceActiveEventsByOneDay();
        List<ShopEventRow> previous = new ArrayList<>(activeCache);
        refreshCache();
        Set<Long> currentIds = new HashSet<>();
        for (ShopEventRow e : activeCache) currentIds.add(e.id);
        for (ShopEventRow e : previous) {
            if (!currentIds.contains(e.id)) announceEnd(e);
        }
        // Forcer le roll quotidien
        lastDailyRollDay = -1;
        attemptDailyRoll();
        Calendar cal = Calendar.getInstance();
        lastDailyRollDay = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        broadcastEventState();
    }

    // ── Sélection pondérée d'items ────────────────────────────────────────────

    private List<Integer> pickWeightedItems(int n) {
        List<ShopItem> all = database.getAllItems();
        if (all.isEmpty()) return Collections.emptyList();

        // Poids = volume 7j (buy + sell) + 1 (pour éviter poids=0)
        double[] weights = new double[all.size()];
        double total = 0.0;
        for (int i = 0; i < all.size(); i++) {
            ShopItem it = all.get(i);
            if (it.frozen) { weights[i] = 0.0; continue; }
            long v = database.getBuyVolumeLast7Days(it.id) + database.getSellVolumeLast7Days(it.id);
            weights[i] = 1.0 + Math.sqrt(v);
            total += weights[i];
        }
        if (total <= 0.0) return Collections.emptyList();

        Set<Integer> picked = new LinkedHashSet<>();
        int safety = 0;
        while (picked.size() < n && safety++ < 200) {
            double pick = rng.nextDouble() * total;
            double acc = 0.0;
            for (int i = 0; i < all.size(); i++) {
                acc += weights[i];
                if (acc >= pick) {
                    if (!picked.contains(all.get(i).id)) picked.add(all.get(i).id);
                    break;
                }
            }
        }
        return new ArrayList<>(picked);
    }

    // ── Lecture du cache ──────────────────────────────────────────────────────

    public List<ShopEventRow> getActiveEvents() { return activeCache; }

    private int countActiveGlobals() {
        int n = 0;
        for (ShopEventRow e : activeCache) if (e.isGlobal()) n++;
        return n;
    }
    private int countActiveAubaines() {
        int n = 0;
        for (ShopEventRow e : activeCache) if (!e.isGlobal()) n++;
        return n;
    }

    private void refreshCache() {
        activeCache = database.getActiveEvents();
    }

    // ── Application des multiplicateurs ───────────────────────────────────────

    /** Multiplicateur global cumulatif sur le prix d'achat (player → shop, ce qu'on PAYE). */
    public double getBuyMultiplier(int itemId) {
        double m = 1.0;
        for (ShopEventRow e : activeCache) {
            if (e.isGlobal() || e.getItemIds().contains(itemId)) {
                m *= effectiveMultForItem(e, itemId, e.multiplierBuy);
            }
        }
        return m;
    }

    /** Multiplicateur global cumulatif sur le prix de vente (shop → player, ce qu'on RECOIT). */
    public double getSellMultiplier(int itemId) {
        double m = 1.0;
        for (ShopEventRow e : activeCache) {
            if (e.isGlobal() || e.getItemIds().contains(itemId)) {
                m *= effectiveMultForItem(e, itemId, e.multiplierSell);
            }
        }
        return m;
    }

    /**
     * Applique une variation déterministe par item lors d'un krach/inflation.
     * Le multiplicateur de base "m" est ajusté ainsi : 1 + (m - 1) × jitter,
     * avec jitter ∈ [0.65, 1.35] selon le hash (eventId, itemId).
     * Ainsi tous les items ne chutent pas (ou grimpent) du même montant.
     * Les aubaines ne sont PAS jitterisées (multiplicateur choisi par le staff).
     */
    public static double effectiveMultForItem(ShopEventRow e, int itemId, double baseMult) {
        if (TYPE_AUBAINE.equals(e.type)) return baseMult;
        double j = jitterFactor(e.type, itemId);
        return 1.0 + (baseMult - 1.0) * j;
    }

    /** Hash déterministe (eventType, itemId) → [0.65, 1.35]. Identique côté client. */
    public static double jitterFactor(String eventType, int itemId) {
        long typeHash = (long) eventType.hashCode();
        long h = (typeHash * 2862933555777941757L) ^ (itemId * 1442695040888963407L);
        h ^= (h >>> 32);
        int u = (int)(h & 0x7FFFFFFF);
        double r = (u % 10000) / 10000.0; // [0, 1)
        return 0.65 + r * 0.70;
    }

    public long effectiveBuyPrice(ShopItem item) {
        double m = getBuyMultiplier(item.id);
        if (m == 1.0) return item.currentBuyPrice;
        long v = (long) Math.round(item.currentBuyPrice * m);
        return Math.max(item.floorPrice + 1, Math.min(item.ceilPrice, v));
    }

    public long effectiveSellPrice(ShopItem item) {
        double m = getSellMultiplier(item.id);
        if (m == 1.0) return item.currentSellPrice;
        long v = (long) Math.round(item.currentSellPrice * m);
        return Math.max(item.floorPrice, v);
    }

    // ── Annonces ──────────────────────────────────────────────────────────────

    private void announceStart(String coloredMsg, String eventType) {
        String msg = ChatColor.translateAlternateColorCodes('&', coloredMsg);
        if (announceBroadcast) Bukkit.broadcastMessage(msg);
        // Title : uniquement pour les events globaux (krach/inflation), pas les aubaines
        if (announceTitle && !TYPE_AUBAINE.equals(eventType)) {
            // Title court par type ; subtitle = durée formatée
            String title;
            if (TYPE_KRACH.equals(eventType))         title = ChatColor.DARK_RED + "" + ChatColor.BOLD + "▼ KRACH BOURSIER";
            else if (TYPE_INFLATION.equals(eventType)) title = ChatColor.GOLD     + "" + ChatColor.BOLD + "▲ INFLATION";
            else                                       title = ChatColor.YELLOW   + "" + ChatColor.BOLD + "ÉVÉNEMENT";

            // Subtitle = phrase d'accroche tronquée (40 chars max sans codes couleur)
            String stripped = ChatColor.stripColor(msg);
            // Retire le préfixe [TAG] s'il existe
            int closeBr = stripped.indexOf(']');
            if (closeBr > 0 && closeBr < 32) stripped = stripped.substring(closeBr + 1).trim();
            if (stripped.length() > 40) stripped = stripped.substring(0, 37) + "…";
            String subtitle = ChatColor.WHITE + stripped;

            for (Player p : Bukkit.getOnlinePlayers()) sendTitle(p, title, subtitle);
        }
    }

    private void announceEnd(ShopEventRow e) {
        String tmpl;
        if (TYPE_KRACH.equals(e.type)) tmpl = cKrach.announceEnd;
        else if (TYPE_INFLATION.equals(e.type)) tmpl = cInflation.announceEnd;
        else tmpl = cAubaine.announceEnd;
        String msg = ChatColor.translateAlternateColorCodes('&', tmpl);
        if (announceBroadcast) Bukkit.broadcastMessage(msg);
    }

    public void notifyJoin(Player p) {
        if (!notifyOnJoin || activeCache.isEmpty()) return;
        p.sendMessage(ChatColor.GOLD + "[Bourse] " + ChatColor.YELLOW + activeCache.size() +
                      " événement(s) boursier(s) actuellement actif(s) :");
        long now = System.currentTimeMillis() / 1000L;
        for (ShopEventRow e : activeCache) {
            long left = Math.max(0, e.endTs - now) / 60L;
            String tag;
            if (TYPE_KRACH.equals(e.type)) tag = ChatColor.DARK_RED + "KRACH";
            else if (TYPE_INFLATION.equals(e.type)) tag = ChatColor.GOLD + "INFLATION";
            else tag = ChatColor.GREEN + "AUBAINE";
            p.sendMessage(" " + ChatColor.GRAY + "• " + tag + ChatColor.GRAY + " (reste " + left + " min)");
        }
        p.sendMessage(ChatColor.GRAY + "Tapez " + ChatColor.WHITE + "/shop" + ChatColor.GRAY + " pour voir le détail.");
    }

    /**
     * Envoie un title via NMS (Spigot 1.8.9). Aucun fallback chat — l'annonce
     * broadcast a déjà été faite si activée. Pas d'erreur silencieuse non plus :
     * on log la cause pour pouvoir diagnostiquer en cas de version Spigot exotique.
     */
    private void sendTitle(Player p, String title, String subtitle) {
        try {
            Object craftPlayer = p.getClass().getMethod("getHandle").invoke(p);
            java.lang.reflect.Field connField = craftPlayer.getClass().getField("playerConnection");
            Object connection = connField.get(craftPlayer);

            Class<?> chatSerializer = Class.forName("net.minecraft.server.v1_8_R3.IChatBaseComponent$ChatSerializer");
            Class<?> baseComponent  = Class.forName("net.minecraft.server.v1_8_R3.IChatBaseComponent");
            Class<?> titlePacket    = Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutTitle");
            Class<?> enumAction     = Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutTitle$EnumTitleAction");

            Object titleJson = chatSerializer.getMethod("a", String.class)
                    .invoke(null, "{\"text\":\"" + escape(title) + "\"}");
            Object subJson = (subtitle != null && !subtitle.isEmpty())
                    ? chatSerializer.getMethod("a", String.class).invoke(null, "{\"text\":\"" + escape(subtitle) + "\"}")
                    : null;

            Object titleAction    = enumAction.getField("TITLE").get(null);
            Object subtitleAction = enumAction.getField("SUBTITLE").get(null);
            Object timesAction    = enumAction.getField("TIMES").get(null);

            // fadeIn / stay / fadeOut en ticks (10 / 50 / 20)
            Object timesPkt = titlePacket.getConstructor(enumAction, baseComponent, int.class, int.class, int.class)
                    .newInstance(timesAction, titleJson, 10, 60, 20);
            java.lang.reflect.Method sendPacket = connection.getClass().getMethod("sendPacket",
                    Class.forName("net.minecraft.server.v1_8_R3.Packet"));
            sendPacket.invoke(connection, timesPkt);

            Object titlePkt = titlePacket.getConstructor(enumAction, baseComponent).newInstance(titleAction, titleJson);
            sendPacket.invoke(connection, titlePkt);
            if (subJson != null) {
                Object subPkt = titlePacket.getConstructor(enumAction, baseComponent).newInstance(subtitleAction, subJson);
                sendPacket.invoke(connection, subPkt);
            }
        } catch (Throwable t) {
            // Une seule fois, sinon spam log
            if (!warnedTitleUnsupported) {
                warnedTitleUnsupported = true;
                LOG.warning("[ShopEvent] Title API indisponible (" + t.getClass().getSimpleName() + "), seul le chat sera utilisé.");
            }
        }
    }

    private static boolean warnedTitleUnsupported = false;

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Push aux clients ──────────────────────────────────────────────────────

    public void broadcastEventState() {
        shopManager.broadcastEventState();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean rollPercent(double pct) {
        if (pct <= 0.0) return false;
        if (pct >= 100.0) return true;
        return rng.nextDouble() * 100.0 < pct;
    }

    private int randInt(int min, int max) {
        if (max <= min) return min;
        return min + rng.nextInt(max - min + 1);
    }

    private double randDouble(double min, double max) {
        if (max <= min) return min;
        return min + rng.nextDouble() * (max - min);
    }

    public static String formatDuration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "h" : h + "h" + m;
    }

    // ── Sous-classes config ───────────────────────────────────────────────────

    private static class EventConfig {
        boolean enabled = true;
        double dailyChance = 7.0;
        int durationMin = 120, durationMax = 360;
        double multBuyMin = 0.6, multBuyMax = 0.8;
        double multSellMin = 0.4, multSellMax = 0.6;
        String announceStart = "&7Event lancé pour {duration}";
        String announceEnd   = "&7Event terminé";

        void load(org.bukkit.configuration.ConfigurationSection cs) {
            if (cs == null) return;
            enabled        = cs.getBoolean("enabled", enabled);
            dailyChance    = cs.getDouble("daily_chance", dailyChance);
            durationMin    = cs.getInt("duration_min", durationMin);
            durationMax    = cs.getInt("duration_max", durationMax);
            multBuyMin     = cs.getDouble("multiplier_buy_min",  multBuyMin);
            multBuyMax     = cs.getDouble("multiplier_buy_max",  multBuyMax);
            multSellMin    = cs.getDouble("multiplier_sell_min", multSellMin);
            multSellMax    = cs.getDouble("multiplier_sell_max", multSellMax);
            announceStart  = cs.getString("announce_start", announceStart);
            announceEnd    = cs.getString("announce_end",   announceEnd);
        }
    }

    private static class AubaineConfig {
        boolean enabled = true;
        double dailyChance = 30.0;
        int durationMin = 60, durationMax = 180;
        int itemsMin = 1, itemsMax = 3;
        double upwardChance = 50.0;
        double upMultMin = 1.5, upMultMax = 2.5;
        double downMultMin = 0.4, downMultMax = 0.6;
        String announceStartUp   = "&aAubaine vente sur {items} ({duration})";
        String announceStartDown = "&aAubaine achat sur {items} ({duration})";
        String announceEnd       = "&7Aubaine terminée";

        void load(org.bukkit.configuration.ConfigurationSection cs) {
            if (cs == null) return;
            enabled            = cs.getBoolean("enabled", enabled);
            dailyChance        = cs.getDouble("daily_chance", dailyChance);
            durationMin        = cs.getInt("duration_min", durationMin);
            durationMax        = cs.getInt("duration_max", durationMax);
            itemsMin           = cs.getInt("items_min", itemsMin);
            itemsMax           = cs.getInt("items_max", itemsMax);
            upwardChance       = cs.getDouble("upward_chance", upwardChance);
            upMultMin          = cs.getDouble("upward_multiplier_min",   upMultMin);
            upMultMax          = cs.getDouble("upward_multiplier_max",   upMultMax);
            downMultMin        = cs.getDouble("downward_multiplier_min", downMultMin);
            downMultMax        = cs.getDouble("downward_multiplier_max", downMultMax);
            announceStartUp    = cs.getString("announce_start_up",   announceStartUp);
            announceStartDown  = cs.getString("announce_start_down", announceStartDown);
            announceEnd        = cs.getString("announce_end",        announceEnd);
        }
    }
}
