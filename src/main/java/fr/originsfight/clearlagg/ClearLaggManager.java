package fr.originsfight.clearlagg;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.regex.Pattern;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Système ClearLagg — supprime périodiquement les entités indésirables.
 *
 * Fonctionnement :
 *  - Toutes les X minutes (configurable) un nettoyage est lancé.
 *  - Un compte à rebours est diffusé en chat N secondes avant (configurable).
 *  - On peut inclure ou exclure chaque type d'entité via la config.
 *  - Par défaut : supprime drops au sol, flèches, expOrbs, mobs hostiles
 *    et neutres courants, SAUF villageois et zombie-villageois.
 *  - Les animaux apprivoisés (chiens/chats) sont exclus par défaut.
 *  - Les entités portant un nom personnalisé (nametag) sont exclus par défaut.
 *  - Les mondes peuvent être exclus via la config.
 */
public class ClearLaggManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Intervalle entre deux clearlagg (minutes). */
    private int intervalMinutes = 5;

    /** Secondes avant le clearlagg où un avertissement est diffusé en chat (deprecated). */
    @Deprecated
    private int warningSeconds = 30;

    /** Liste de warnings (en secondes avant le clear) à diffuser. */
    private List<Integer> warningSecondsList = new ArrayList<>();

    /** Envoyer aussi un title en plus du message chat pour chaque avertissement. */
    private boolean warningUseTitle = true;

    /** Durées du title (ticks). */
    private int titleFadeIn = 5, titleStay = 40, titleFadeOut = 5;

    /** Supprimer les items droppés au sol. */
    private boolean clearItems = true;

    /** Supprimer les flèches tirées. */
    private boolean clearArrows = true;

    /** Supprimer les orbes d'expérience. */
    private boolean clearExpOrbs = true;

    /**
     * Types de mobs à supprimer (noms d'EntityType en majuscule).
     * Défaut : tous les mobs classiques SAUF villageois et zombie-villageois.
     */
    private List<String> mobsToClear = new ArrayList<>();

    /**
     * Types de mobs explicitement exclus même s'ils sont dans mobsToClear
     * ou dans la catégorie "tous les mobs".
     */
    private List<String> excludedMobs = new ArrayList<>();

    /** Noms de mondes où le clearlagg ne s'applique PAS. */
    private List<String> excludedWorlds = new ArrayList<>();

    /** Protège les entités avec un nametag personnalisé. */
    private boolean protectNamedEntities = true;

    /** Protège les animaux apprivoisés (tamed). */
    private boolean protectTamedAnimals = true;

    /** Si true, supprime TOUS les mobs (sauf exclus/nommés/apprivoisés). */
    private boolean clearAllMobs = false;

    /** Logs de debug. */
    private boolean debugMode = false;

    // ----- Détection MobStacker -----
    /** Activer la détection des mobs "stackés" par des plugins (MobStacker etc.) */
    private boolean detectMobStacker = true;
    /** Liste de clés metadata (heuristique) à vérifier sur l'entité */
    private List<String> mobStackerKeys = new ArrayList<>();
    /** Si true, les entités nommées détectées comme "stackées" pourront être supprimées */
    private boolean forceClearNamedStacked = true;
    /** Pattern pour détecter un nom contenant un compteur (ex: "(5) Zombie" ou "Zombie x5") */
    private Pattern stackNamePattern = Pattern.compile("(?i).*(?:\\b|\\(|\\[|x|×)\\s*\\d+\\s*(?:\\)|\\]|\\b).*");

    // ── État interne ──────────────────────────────────────────────────────────

    private final OriginsFightCore plugin;
    private BukkitTask warningTask;

    /** Timestamp (ms) du prochain clearlagg. */
    private long nextClearMs = -1;

    /**
     * Set des avertissements (en secondes) déjà envoyés pour le cycle en cours.
     * Réinitialisé à chaque début de cycle pour éviter les doublons.
     * Utilisation de ConcurrentHashMap.newKeySet() pour la thread-safety.
     */
    private final Set<Integer> sentWarnings = ConcurrentHashMap.newKeySet();

    public ClearLaggManager(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void enable() {
        loadConfig();
        scheduleAll();
        plugin.getLogger().info("[ClearLagg] Activé — intervalle=" + intervalMinutes
                + "min, countdowns=" + warningSecondsList
                + ", titles=" + warningUseTitle
                + ", clearItems=" + clearItems
                + ", clearArrows=" + clearArrows
                + ", clearExpOrbs=" + clearExpOrbs
                + ", clearAllMobs=" + clearAllMobs);
    }

    public void disable() {
        cancelTasks();
        plugin.getLogger().info("[ClearLagg] Désactivé.");
    }

    public void reload() {
        loadConfig();
        scheduleAll();
        plugin.getLogger().info("[ClearLagg] Rechargé.");
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public void loadConfig() {
        intervalMinutes      = plugin.getConfig().getInt("clearlagg.interval-minutes",   5);
        warningSeconds       = plugin.getConfig().getInt("clearlagg.warning-seconds",    30);

        // New: list of warning seconds (allows multiple warnings). Backwards compatible
        List<Integer> list = plugin.getConfig().getIntegerList("clearlagg.warning-seconds-list");
        warningSecondsList.clear();
        if (list != null && !list.isEmpty()) {
            warningSecondsList.addAll(list);
        } else {
            // Back-compat: use single warningSeconds if list not provided
            warningSecondsList.add(warningSeconds);
        }

        clearItems           = plugin.getConfig().getBoolean("clearlagg.clear-items",    true);
        clearArrows          = plugin.getConfig().getBoolean("clearlagg.clear-arrows",   true);
        clearExpOrbs         = plugin.getConfig().getBoolean("clearlagg.clear-exp-orbs", true);
        clearAllMobs         = plugin.getConfig().getBoolean("clearlagg.clear-all-mobs", false);
        protectNamedEntities = plugin.getConfig().getBoolean("clearlagg.protect-named",  true);
        protectTamedAnimals  = plugin.getConfig().getBoolean("clearlagg.protect-tamed",  true);
        debugMode            = plugin.getConfig().getBoolean("clearlagg.debug",          false);

        // Title options
        warningUseTitle      = plugin.getConfig().getBoolean("clearlagg.warning-use-title", true);
        titleFadeIn          = plugin.getConfig().getInt("clearlagg.title-fade-in", 5);
        titleStay            = plugin.getConfig().getInt("clearlagg.title-stay", 40);
        titleFadeOut         = plugin.getConfig().getInt("clearlagg.title-fade-out", 5);

        // --- MobStacker options ---
        detectMobStacker = plugin.getConfig().getBoolean("clearlagg.detect-mobstacker", true);
        mobStackerKeys = plugin.getConfig().getStringList("clearlagg.mobstacker-metadata-keys");
        forceClearNamedStacked = plugin.getConfig().getBoolean("clearlagg.force-clear-named-stacked", true);

        mobsToClear    = plugin.getConfig().getStringList("clearlagg.mobs-to-clear");
        excludedMobs   = plugin.getConfig().getStringList("clearlagg.excluded-mobs");
        excludedWorlds = plugin.getConfig().getStringList("clearlagg.excluded-worlds");

        // Defaults for mobStackerKeys if none provided
        if (mobStackerKeys == null || mobStackerKeys.isEmpty()) {
            mobStackerKeys = new ArrayList<>(Arrays.asList(
                    "stack", "stacked", "mobstack", "mobstacker", "stackamount", "stack_size"
            ));
        }

        // If mobsToClear is empty, provide sensible defaults (common mobs & animals excluding villagers)
        if (mobsToClear == null || mobsToClear.isEmpty()) {
            mobsToClear = new ArrayList<>(Arrays.asList(
                    "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "ENDERMAN",
                    "WITCH", "SLIME", "MAGMA_CUBE", "BLAZE", "GHAST", "SILVERFISH",
                    "ENDERMITE", "GUARDIAN", "SHULKER", "SQUID", "BAT",
                    "WOLF", "OCELOT", "RABBIT", "PIG", "SHEEP", "COW", "CHICKEN", "HORSE", "LLAMA", "POLAR_BEAR"
            ));
        }

        // Normaliser
        mobsToClear.replaceAll(String::toUpperCase);
        excludedMobs.replaceAll(String::toUpperCase);
        excludedWorlds.replaceAll(String::toLowerCase);
        mobStackerKeys.replaceAll(String::toLowerCase);

        // Ensure warning list is sorted ascending (smallest first) and unique
        Collections.sort(warningSecondsList);
        List<Integer> uniq = new ArrayList<>();
        for (int s : warningSecondsList) if (!uniq.contains(s) && s > 0) uniq.add(s);
        warningSecondsList = uniq;
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private void scheduleAll() {
        cancelTasks();
        sentWarnings.clear();

        long intervalMs = (long) intervalMinutes * 60 * 1000L;

        nextClearMs = System.currentTimeMillis() + intervalMs;

        warningTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long secsLeft = getSecondsUntilNext();

            // Heure du clear !
            if (secsLeft <= 0) {
                sentWarnings.clear();
                nextClearMs = System.currentTimeMillis() + intervalMs;
                runClearLagg();
                return;
            }

            // Warnings
            for (int warn : warningSecondsList) {
                if (secsLeft <= warn && !sentWarnings.contains(warn)) {
                    sentWarnings.add(warn);
                    sendWarning(warn);
                }
            }
        }, 20L, 20L); // toutes les secondes (temps réel)
    }

    private void cancelTasks() {
        if (warningTask != null) { warningTask.cancel(); warningTask = null; }
        sentWarnings.clear();
    }

    // ── Avertissement ─────────────────────────────────────────────────────────

    private void sendWarning(int seconds) {
        String timeText = (seconds >= 60 && seconds % 60 == 0)
                ? (seconds / 60) + " minute(s)"
                : seconds + " seconde(s)";

        String chat = "§8[§6§lClearLagg§8] §eLes entités seront supprimées dans §f"
                + timeText + " §e!";
        Bukkit.broadcastMessage(chat);

        if (warningUseTitle) {
            String title    = "§6§lClearLagg";
            String subtitle = "§eDans " + timeText;
            for (Player p : Bukkit.getOnlinePlayers()) {
                sendTitle(p, title, subtitle);
            }
        }
    }

    /**
     * Envoie un title/subtitle au joueur.
     * Spigot 1.8.8 expose Player#sendTitle(String, String) directement sur l'interface.
     * On essaie d'abord la signature avec fade-times (Spigot 1.9+), puis sans (Spigot 1.8),
     * puis NMS en dernier recours, avant de tomber sur le chat.
     */
    private void sendTitle(Player p, String title, String subtitle) {
        // 1) Spigot ≥1.9 : sendTitle(String, String, int, int, int)
        try {
            p.getClass().getMethod("sendTitle", String.class, String.class, int.class, int.class, int.class)
             .invoke(p, title, subtitle, titleFadeIn, titleStay, titleFadeOut);
            return;
        } catch (NoSuchMethodException ignored) {
            // pas disponible sur cette version
        } catch (Exception e) {
            if (debugMode) plugin.getLogger().warning("[ClearLagg] sendTitle (5-arg) erreur : " + e.getMessage());
        }

        // 2) Spigot 1.8 : sendTitle(String, String)
        try {
            p.getClass().getMethod("sendTitle", String.class, String.class)
             .invoke(p, title, subtitle);
            return;
        } catch (NoSuchMethodException ignored) {
            // pas disponible sur cette version
        } catch (Exception e) {
            if (debugMode) plugin.getLogger().warning("[ClearLagg] sendTitle (2-arg) erreur : " + e.getMessage());
        }

        // 3) NMS PacketPlayOutTitle (fonctionne sur CraftBukkit/Spigot 1.8.x)
        try {
            String ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object conn   = handle.getClass().getField("playerConnection").get(handle);

            Class<?> chatSerClass  = Class.forName("net.minecraft.server." + ver + ".ChatSerializer");
            if (!doesClassExist("net.minecraft.server." + ver + ".ChatSerializer"))
                chatSerClass = Class.forName("net.minecraft.server." + ver + ".IChatBaseComponent$ChatSerializer");

            Class<?> iChatClass    = Class.forName("net.minecraft.server." + ver + ".IChatBaseComponent");
            Class<?> packetClass   = Class.forName("net.minecraft.server." + ver + ".PacketPlayOutTitle");
            Class<?> packetClass2  = Class.forName("net.minecraft.server." + ver + ".Packet");

            Class<?> enumClass = null;
            for (Class<?> c : packetClass.getDeclaredClasses()) {
                if (c.isEnum()) { enumClass = c; break; }
            }
            if (enumClass == null) throw new IllegalStateException("EnumTitleAction introuvable");

            Method a = chatSerClass.getMethod("a", String.class);
            Method sendPkt = conn.getClass().getMethod("sendPacket", packetClass2);

            // TIMES
            Object timesPacket = packetClass
                .getConstructor(enumClass, iChatClass, int.class, int.class, int.class)
                .newInstance(Enum.valueOf((Class<Enum>) enumClass, "TIMES"), null, titleFadeIn, titleStay, titleFadeOut);
            sendPkt.invoke(conn, timesPacket);

            // TITLE
            Object titleComp = a.invoke(null, "{\"text\":\"" + escapeJson(title) + "\"}");
            Object titlePacket = packetClass
                .getConstructor(enumClass, iChatClass)
                .newInstance(Enum.valueOf((Class<Enum>) enumClass, "TITLE"), titleComp);
            sendPkt.invoke(conn, titlePacket);

            // SUBTITLE
            Object subComp = a.invoke(null, "{\"text\":\"" + escapeJson(subtitle) + "\"}");
            Object subPacket = packetClass
                .getConstructor(enumClass, iChatClass)
                .newInstance(Enum.valueOf((Class<Enum>) enumClass, "SUBTITLE"), subComp);
            sendPkt.invoke(conn, subPacket);
            return;
        } catch (Exception e) {
            if (debugMode) plugin.getLogger().warning("[ClearLagg] sendTitle NMS erreur : " + e.getMessage());
        }

        // 4) Fallback chat
        p.sendMessage("§6[ClearLagg] " + subtitle);
    }

    private boolean doesClassExist(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** Échappe les guillemets pour le JSON du composant de chat NMS. */
    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Clearlagg ─────────────────────────────────────────────────────────────

    /**
     * Lance le clearlagg immédiatement (utilisé aussi par la commande /clearlagg now).
     * @return nombre total d'entités supprimées
     */
    public int runClearLagg() {
        int total = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                if (excludedWorlds.contains(world.getName().toLowerCase())) {
                    if (debugMode) plugin.getLogger().info("[ClearLagg] Monde ignoré : " + world.getName());
                    continue;
                }
                int count = clearWorld(world);
                total += count;
                if (debugMode) plugin.getLogger().info("[ClearLagg] " + world.getName() + " → " + count + " entités supprimées");
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("[ClearLagg] Erreur inattendue lors du clearlagg : " + t.getMessage());
            if (debugMode) t.printStackTrace();
        } finally {
            // Always broadcast result (even if partial) so admins know the task ran
            Bukkit.broadcastMessage("§8[§6§lClearLagg§8] §aNettoyage terminé — §f"
                    + total + " §aentité(s) supprimée(s).");

            // Log to console as well so admins see it in server logs
            plugin.getLogger().info("[ClearLagg] Nettoyage terminé — " + total + " entité(s) supprimée(s).");

            // send title on completion if configured
            if (warningUseTitle) {
                String title = "§6§lClearLagg";
                String subtitle = "§aNettoyage terminé — §f" + total + "§a entité(s)";
                for (Player p : Bukkit.getOnlinePlayers()) {
                    sendTitle(p, title, subtitle);
                }
            }
        }
        return total;
    }

    private int clearWorld(World world) {
        int count = 0;
        // Collect entities to remove first to avoid concurrent modification and to catch errors per-entity.
        List<Entity> toRemove = new ArrayList<>();
        try {
            for (Entity entity : world.getEntities()) {
                try {
                    if (shouldRemove(entity)) {
                        toRemove.add(entity);
                    }
                } catch (Throwable t) {
                    if (debugMode) plugin.getLogger().warning("[ClearLagg] Erreur lors du test de l'entité " + entity + ": " + t.getMessage());
                }
            }

            // Detailed debug: count entities by type before removal
            if (debugMode) {
                Map<String, Integer> preRemoveCount = new HashMap<>();
                for (Entity e : toRemove) {
                    String key = e.getType().name();
                    preRemoveCount.put(key, preRemoveCount.getOrDefault(key, 0) + 1);
                }
                plugin.getLogger().info("[ClearLagg] Entités à supprimer (avant) : " + preRemoveCount);
            }

            for (Entity e : toRemove) {
                try {
                    e.remove();
                    count++;
                } catch (Throwable t) {
                    if (debugMode) plugin.getLogger().warning("[ClearLagg] Erreur lors de la suppression de l'entité " + e + ": " + t.getMessage());
                }
            }

            // Detailed debug: count remaining entities by type after removal
            if (debugMode) {
                Map<String, Integer> postRemoveCount = new HashMap<>();
                for (Entity e : world.getEntities()) {
                    String key = e.getType().name();
                    postRemoveCount.put(key, postRemoveCount.getOrDefault(key, 0) + 1);
                }
                plugin.getLogger().info("[ClearLagg] Entités restantes (après) : " + postRemoveCount);
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("[ClearLagg] Exception lors du nettoyage du monde " + world.getName() + ": " + t.getMessage());
            if (debugMode) t.printStackTrace();
        }
        return count;
    }

    /**
     * Décide si une entité doit être supprimée.
     */
    private boolean shouldRemove(Entity entity) {
        // Ne jamais supprimer les joueurs
        if (entity instanceof Player) return false;

        // Protéger les entités nommées (nametag personnalisé)
        boolean hasCustomName = entity.getCustomName() != null && !entity.getCustomName().isEmpty();
        boolean isStacked = detectMobStacker && isStackedEntity(entity);

        if (protectNamedEntities && hasCustomName) {
            // Si c'est un mob "stacké" et qu'on autorise la suppression des nommés stackés,
            // on laisse passer la vérification plus loin. Sinon on protège cette entité.
            if (!isStacked || (isStacked && !forceClearNamedStacked)) {
                return false;
            }
        }

        // Protéger les animaux apprivoisés
        if (protectTamedAnimals && entity instanceof Tameable) {
            if (((Tameable) entity).isTamed()) return false;
        }

        // Spécial : Zombie-villageois (souvent de type ZOMBIE) — respecter l'exclusion
        try {
            if (entity instanceof Zombie) {
                Zombie z = (Zombie) entity;
                try {
                    if (z.isVillager()) {
                        if (excludedMobs.contains("ZOMBIE_VILLAGER") || excludedMobs.contains("VILLAGER")) {
                            return false;
                        }
                    }
                } catch (NoSuchMethodError ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        String typeName = entity.getType().name().toUpperCase();

        // Toujours exclure si dans la liste d'exclusion
        if (excludedMobs.contains(typeName)) return false;

        // Items droppés
        if (entity instanceof Item) return clearItems;

        // Flèches
        if (entity instanceof Arrow) return clearArrows;

        // Orbes d'XP
        if (entity instanceof ExperienceOrb) return clearExpOrbs;

        // Mobs vivants (hors joueur)
        if (entity instanceof LivingEntity) {
            if (clearAllMobs) return true;
            return mobsToClear.contains(typeName);
        }

        return false;
    }

    /**
     * Heuristiques pour détecter un mob "stacké" par un plugin type MobStacker.
     * - Metadata : si l'entité a une metadata dont la clé contient l'une des clés configurées.
     * - Nom personnalisé : si le nom contient un compteur (ex: "(5) Zombie", "Zombie x5").
     */
    private boolean isStackedEntity(Entity entity) {
        try {
            // Vérifie metadata (si le plugin a posé une metadata connue)
            for (String key : mobStackerKeys) {
                if (entity.hasMetadata(key)) return true;
            }
        } catch (NoSuchMethodError ignored) {
            // En 1.8 certains environnements peuvent ne pas supporter certaines méthodes, ignore.
        }

        // Vérifie le nom (heuristique)
        String name = entity.getCustomName();
        if (name != null && stackNamePattern.matcher(name).matches()) return true;

        return false;
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Secondes jusqu'au prochain clearlagg.
     * Retourne -1 si le scheduler n'est pas actif.
     */
    public long getSecondsUntilNext() {
        if (nextClearMs < 0) return -1;
        long diff = nextClearMs - System.currentTimeMillis();
        return diff > 0 ? diff / 1000L : 0L;
    }

    // ── Getters config (pour la commande /clearlagg info) ────────────────────

    public int getIntervalMinutes()        { return intervalMinutes; }
    public int getWarningSeconds()         { return warningSeconds; }
    public List<Integer> getWarningSecondsList() { return Collections.unmodifiableList(warningSecondsList); }
    public boolean isWarningUseTitle()     { return warningUseTitle; }
    public boolean isClearItems()          { return clearItems; }
    public boolean isClearArrows()         { return clearArrows; }
    public boolean isClearExpOrbs()        { return clearExpOrbs; }
    public boolean isClearAllMobs()        { return clearAllMobs; }
    public boolean isProtectNamed()        { return protectNamedEntities; }
    public boolean isProtectTamed()        { return protectTamedAnimals; }
    public List<String> getMobsToClear()   { return Collections.unmodifiableList(mobsToClear); }
    public List<String> getExcludedMobs()  { return Collections.unmodifiableList(excludedMobs); }
    public List<String> getExcludedWorlds(){ return Collections.unmodifiableList(excludedWorlds); }
    public boolean isDetectMobStacker()    { return detectMobStacker; }
    public List<String> getMobStackerKeys(){ return Collections.unmodifiableList(mobStackerKeys); }
    public boolean isForceClearNamedStacked(){ return forceClearNamedStacked; }
}
