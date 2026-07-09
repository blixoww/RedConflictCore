package fr.redconflict.lagswitch;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Système anti Lag-Switch / Crash-Connection pour Spigot 1.8.9.
 *
 * Logique de détection (deux critères CUMULATIFS, pas alternatifs) :
 *
 *  CRITÈRE A — Ping absolu élevé (configurable, défaut 1200ms)
 *    Le ping NMS dépasse le seuil pendant N vérifications consécutives.
 *    → Élimine les pics ponctuels de lag naturel (1 ou 2 checks isolés).
 *
 *  CRITÈRE B — Saut brutal du ping (delta, configurable, défaut 600ms/check)
 *    Le ping a augmenté de plus de X ms en une seule vérification par rapport
 *    au dernier ping mémorisé.
 *    → Distingue un lag-switch (montée quasi-instantanée) d'une connexion
 *      simplement mauvaise (montée progressive).
 *
 *  Déclenchement si : A ET B sont vrais (OU si uniquement A après un grand nombre
 *  de checks consécutifs = seuil élevé prolongé, configurable).
 *
 *  pendingKeepalive : utilisé UNIQUEMENT en indicateur dans les logs/infos,
 *  JAMAIS comme critère de déclenchement seul.
 *
 *  Sanctions : désactivées par défaut. Le système signale + freeze ; un admin
 *  décide manuellement via /lagswitch unfreeze.
 */
public class LagSwitchManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Ping absolu au-delà duquel on commence à suspecter (ms). Défaut 1200. */
    private int pingThreshold = 1200;

    /**
     * Variation brutale minimale du ping en une seule vérification (ms).
     * Un saut de moins que ça = lag naturel progressif, pas un lag-switch.
     * Défaut 600ms.
     */
    private int pingDeltaThreshold = 600;

    /**
     * Nombre de vérifications consécutives au-dessus du seuil absolu SEUL
     * (sans delta) avant de déclencher quand même (connexion durablement coupée).
     * Défaut 8 (= ~2 secondes avec check-interval=5).
     */
    private int ticksBeforeTriggerHard = 8;

    /**
     * Nombre de vérifications consécutives pour le déclenchement "rapide"
     * quand ping absolu + delta sont tous les deux vrais.
     * Défaut 3.
     */
    private int ticksBeforeTriggerFast = 3;

    /** Durée de la grace-period après retour de la connexion (ms). Défaut 3s. */
    private long gracePeriodMs = 3_000;

    /** Intervalle de vérification (ticks). Défaut 10 (2×/s). */
    private int checkIntervalTicks = 10;

    /** Kick auto : 0 = désactivé (défaut). */
    private int incidentsBeforeKick = 0;

    /** Ban auto : 0 = désactivé (défaut). */
    private int incidentsBeforeBan = 0;

    /** Durée du ban temporaire (secondes). */
    private int banDurationSeconds = 300;

    /** Rubber-band pendant le lag. Défaut true. */
    private boolean rubberBandEnabled = true;

    /** Logs de debug. Défaut false. */
    private boolean debugMode = false;

    // ── État interne ─────────────────────────────────────────────────────────

    private final Map<UUID, LagSwitchState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> incidents      = new ConcurrentHashMap<>();
    /** Pings précédents mémorisés pour calculer le delta. */
    private final Map<UUID, Integer> lastPing       = new ConcurrentHashMap<>();
    /** Joueurs freezés manuellement par un admin (non soumis à unfreeze automatique). */
    private final Set<UUID> manualFreeze            = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final RedConflictCore plugin;
    private BukkitTask checkTask;
    private String nmsVersion;

    // ── Reflection cache ──────────────────────────────────────────────────────

    private Method craftGetHandle;
    private Field  entityPingField;
    private Field  playerConnectionField;
    private Field  pendingKeepaliveField;
    private boolean reflectionInitialized = false;

    private int scanIndex = 0;

    public LagSwitchManager(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void enable() {
        nmsVersion = Bukkit.getServer().getClass().getPackage().getName()
                .replace(".", ",").split(",")[3];
        loadConfig();
        initReflection();
        startCheckTask();
        plugin.getLogger().info("[LagSwitch] Activé — seuil=" + pingThreshold
                + "ms, delta=" + pingDeltaThreshold
                + "ms, hard=" + ticksBeforeTriggerHard + " checks"
                + ", kicks=" + (incidentsBeforeKick > 0 ? "oui" : "non")
                + ", bans=" + (incidentsBeforeBan > 0 ? "oui" : "non"));
    }

    public void disable() {
        if (checkTask != null) checkTask.cancel();
        states.clear();
        incidents.clear();
        lastPing.clear();
        manualFreeze.clear();
        plugin.getLogger().info("[LagSwitch] Désactivé.");
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    private void loadConfig() {
        pingThreshold          = plugin.getConfig().getInt("lagswitch.ping-threshold-ms",       1200);
        pingDeltaThreshold     = plugin.getConfig().getInt("lagswitch.ping-delta-threshold-ms",  600);
        ticksBeforeTriggerFast = plugin.getConfig().getInt("lagswitch.ticks-before-trigger",       3);
        ticksBeforeTriggerHard = plugin.getConfig().getInt("lagswitch.ticks-before-trigger-hard",  8);
        gracePeriodMs          = plugin.getConfig().getLong("lagswitch.grace-period-ms",        3_000L);
        checkIntervalTicks     = plugin.getConfig().getInt("lagswitch.check-interval-ticks",      10);
        incidentsBeforeKick    = plugin.getConfig().getInt("lagswitch.incidents-before-kick",       0);
        incidentsBeforeBan     = plugin.getConfig().getInt("lagswitch.incidents-before-ban",        0);
        banDurationSeconds     = plugin.getConfig().getInt("lagswitch.ban-duration-seconds",      300);
        rubberBandEnabled      = plugin.getConfig().getBoolean("lagswitch.rubber-band",          true);
        debugMode              = plugin.getConfig().getBoolean("lagswitch.debug",               false);
    }

    // ── Reflection ────────────────────────────────────────────────────────────

    private void initReflection() {
        try {
            Class<?> craftPlayerCls = Class.forName(
                    "org.bukkit.craftbukkit." + nmsVersion + ".entity.CraftPlayer");
            craftGetHandle = craftPlayerCls.getMethod("getHandle");

            Class<?> entityPlayerCls = Class.forName(
                    "net.minecraft.server." + nmsVersion + ".EntityPlayer");
            entityPingField = entityPlayerCls.getField("ping");
            playerConnectionField = entityPlayerCls.getField("playerConnection");

            Class<?> pcCls = Class.forName(
                    "net.minecraft.server." + nmsVersion + ".PlayerConnection");
            for (Field f : pcCls.getDeclaredFields()) {
                String n = f.getName();
                if (n.equals("pendingKeepalive") || n.equals("e")
                        || n.toLowerCase().contains("keepalive")) {
                    f.setAccessible(true);
                    pendingKeepaliveField = f;
                    break;
                }
            }
            reflectionInitialized = true;
            plugin.getLogger().info("[LagSwitch] Reflection NMS initialisée (v=" + nmsVersion + ").");
        } catch (Exception e) {
            reflectionInitialized = false;
            plugin.getLogger().warning("[LagSwitch] Reflection NMS échouée — détection ping désactivée: " + e.getMessage());
        }
    }

    // ── Tâche de scan ─────────────────────────────────────────────────────────

    private void startCheckTask() {
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            int total = players.size();
            if (total == 0) return;

            int runsPerSecond = Math.max(1, 20 / Math.max(1, checkIntervalTicks));
            int batchSize = Math.max(1, (int) Math.ceil((double) total / runsPerSecond));

            for (int i = 0; i < batchSize; i++) {
                int idx = (scanIndex + i) % total;
                Player p = players.get(idx);
                if (p == null || p.isOp()) continue;
                checkPlayer(p);
            }
            scanIndex = (scanIndex + batchSize) % Math.max(1, total);
        }, checkIntervalTicks, checkIntervalTicks);
    }

    // ── Logique de détection ──────────────────────────────────────────────────

    private void checkPlayer(Player player) {
        if (!reflectionInitialized) return;

        int ping = getNmsPing(player);
        if (ping < 0) return;

        UUID uuid = player.getUniqueId();

        // Calculer le delta par rapport au dernier ping connu
        int prevPing = lastPing.getOrDefault(uuid, ping);
        int delta = ping - prevPing; // positif = le ping a monté
        lastPing.put(uuid, ping);

        LagSwitchState state = states.computeIfAbsent(uuid, k -> new LagSwitchState());
        boolean wasLagging = state.isLagging();

        boolean aboveThreshold = (ping >= pingThreshold);
        boolean brutalSpike    = (delta >= pingDeltaThreshold);

        if (debugMode) {
            plugin.getLogger().info("[LagSwitch:debug] " + player.getName()
                    + " ping=" + ping + "ms delta=" + delta
                    + "ms consec=" + state.consecutiveHighPingTicks
                    + " lagging=" + wasLagging);
        }

        if (aboveThreshold) {
            state.consecutiveHighPingTicks++;

            boolean triggerFast = brutalSpike
                    && state.consecutiveHighPingTicks >= ticksBeforeTriggerFast;
            boolean triggerHard = state.consecutiveHighPingTicks >= ticksBeforeTriggerHard;

            if (!wasLagging && (triggerFast || triggerHard)) {
                String reason = triggerFast ? "spike brutal" : "seuil prolongé";
                triggerLagSwitch(player, state, ping, delta, reason);
            } else if (wasLagging && rubberBandEnabled) {
                rubberBand(player, state);
            }
        } else {
            // Ping redescendu — réinitialiser le compteur
            state.consecutiveHighPingTicks = 0;
            if (wasLagging) {
                endLagSwitch(player, state);
            }
        }
    }

    // ── Gestion des états ─────────────────────────────────────────────────────

    private void triggerLagSwitch(Player player, LagSwitchState state,
                                  int ping, int delta, String reason) {
        state.laggingStartMs  = System.currentTimeMillis();
        state.freezeLocation  = player.getLocation().clone();
        state.graceEndMs      = 0;

        int count   = incidents.merge(player.getUniqueId(), 1, Integer::sum);
        int pending = getPendingKeepalive(player);

        plugin.getLogger().warning("[LagSwitch] DÉTECTION " + player.getName()
                + " | raison=" + reason
                + " | ping=" + ping + "ms"
                + " | delta=+" + delta + "ms"
                + " | keepalive_en_attente=" + pending
                + " | incident #" + count);

        player.sendMessage("§8[§c§lAntiLag§8] §eConnexion instable détectée — "
                + "vos actions sont temporairement suspendues.");

        alertStaff(player, ping, delta, count, reason);

        if (rubberBandEnabled) rubberBand(player, state);

        // Sanctions auto (désactivées par défaut)
        if (incidentsBeforeBan > 0 && count >= incidentsBeforeBan) {
            Bukkit.getScheduler().runTask(plugin, () -> applyBan(player, count));
        } else if (incidentsBeforeKick > 0 && count >= incidentsBeforeKick) {
            Bukkit.getScheduler().runTask(plugin, () -> applyKick(player, count));
        }
    }

    private void endLagSwitch(Player player, LagSwitchState state) {
        long lagDurationMs = System.currentTimeMillis() - state.laggingStartMs;
        state.laggingStartMs = 0;
        state.graceEndMs = System.currentTimeMillis()
                + Math.max(gracePeriodMs, lagDurationMs / 2);

        if (debugMode) plugin.getLogger().info("[LagSwitch] " + player.getName()
                + " connexion rétablie — grace "
                + (state.graceEndMs - System.currentTimeMillis()) + "ms");

        if (rubberBandEnabled && state.freezeLocation != null) {
            final Location safeLoc = state.freezeLocation.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.teleport(safeLoc);
            });
        }
        state.freezeLocation = null;
    }

    private void rubberBand(Player player, LagSwitchState state) {
        if (state.freezeLocation == null) return;
        final Location loc = state.freezeLocation.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.teleport(loc);
        });
    }

    // ── NMS helpers ──────────────────────────────────────────────────────────

    public int getNmsPing(Player player) {
        try {
            if (!reflectionInitialized) return -1;
            Object ep = craftGetHandle.invoke(player);
            return (int) entityPingField.get(ep);
        } catch (Exception e) {
            if (debugMode) plugin.getLogger().warning("[LagSwitch] getNmsPing: " + e.getMessage());
            return -1;
        }
    }

    public int getPendingKeepalive(Player player) {
        try {
            if (!reflectionInitialized || pendingKeepaliveField == null) return 0;
            Object ep  = craftGetHandle.invoke(player);
            Object pc  = playerConnectionField.get(ep);
            Object val = pendingKeepaliveField.get(pc);
            if (val instanceof Integer) return (Integer) val;
            if (val instanceof Short)   return ((Short) val).intValue();
            if (val instanceof Long)    return (int) ((Long) val).longValue();
            return 0;
        } catch (Exception e) {
            if (debugMode) plugin.getLogger().warning("[LagSwitch] getPendingKeepalive: " + e.getMessage());
            return 0;
        }
    }

    // ── Sanctions ─────────────────────────────────────────────────────────────

    private void applyKick(Player player, int count) {
        if (!player.isOnline()) return;
        plugin.getLogger().warning("[LagSwitch] Kick automatique de " + player.getName()
                + " (incidents=" + count + ")");
        player.kickPlayer("§c§lRedConflict §r§c— Anti Lag-Switch\n\n"
                + "§7Connexion abusive détectée.\n"
                + "§7Incidents : §c" + count);
    }

    private void applyBan(Player player, int count) {
        if (!player.isOnline()) return;
        plugin.getLogger().warning("[LagSwitch] Ban automatique de " + player.getName()
                + " (" + banDurationSeconds + "s, incidents=" + count + ")");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "tempban " + player.getName() + " " + banDurationSeconds + "s "
                + "[Auto] Anti-LagSwitch (" + count + " incidents)");
        if (player.isOnline()) {
            player.kickPlayer("§c§lRedConflict §r§c— Anti Lag-Switch\n\n"
                    + "§7Ban temporaire de §f" + banDurationSeconds + "s.\n"
                    + "§7Incidents : §c" + count);
        }
    }

    private void alertStaff(Player target, int ping, int delta, int count, String reason) {
        String msg = "§8[§c§lAntiLag§8] §f" + target.getName()
                + " §7— " + reason
                + " §8(ping=" + ping + "ms"
                + (delta >= pingDeltaThreshold ? ", delta=+" + delta + "ms" : "")
                + ", incident #" + count + ")"
                + " §e/lagswitch unfreeze " + target.getName();
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.isOp() || s.hasPermission("redconflict.staff")) s.sendMessage(msg);
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public boolean isLagging(Player player) {
        LagSwitchState s = states.get(player.getUniqueId());
        return s != null && s.isLagging();
    }

    public boolean isInGrace(Player player) {
        LagSwitchState s = states.get(player.getUniqueId());
        return s != null && s.isInGrace();
    }

    public boolean isRestricted(Player player) {
        if (player.isOp()) return false;
        if (manualFreeze.contains(player.getUniqueId())) return true;
        LagSwitchState s = states.get(player.getUniqueId());
        return s != null && (s.isLagging() || s.isInGrace());
    }

    public boolean isManuallFrozen(UUID uuid) {
        return manualFreeze.contains(uuid);
    }

    /**
     * Freeze manuel par un admin (indépendant de la détection automatique).
     * Mémorise la position actuelle du joueur comme freeze-loc.
     */
    public void manualFreeze(Player player) {
        manualFreeze.add(player.getUniqueId());
        LagSwitchState state = states.computeIfAbsent(player.getUniqueId(), k -> new LagSwitchState());
        if (state.freezeLocation == null) {
            state.freezeLocation = player.getLocation().clone();
        }
    }

    /**
     * Libère complètement un joueur : retire freeze auto + manuel + grace + incidents.
     */
    public void unfreeze(UUID uuid) {
        manualFreeze.remove(uuid);
        states.remove(uuid);
        // On garde les incidents pour l'historique — à reset séparément si besoin
    }

    public void resetPlayer(UUID uuid) {
        states.remove(uuid);
        lastPing.remove(uuid);
        manualFreeze.remove(uuid);
    }

    public void resetIncidents(UUID uuid) {
        incidents.remove(uuid);
    }

    public int getIncidents(UUID uuid)  { return incidents.getOrDefault(uuid, 0); }
    public int getPing(Player player)   { return getNmsPing(player); }
    public int getPingThreshold()       { return pingThreshold; }
    public int getPingDeltaThreshold()  { return pingDeltaThreshold; }
    public boolean isDebugMode()        { return debugMode; }
}
