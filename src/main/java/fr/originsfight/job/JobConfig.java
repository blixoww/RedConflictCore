package fr.originsfight.job;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Charge et expose la configuration du système de métiers depuis jobs.yml.
 *
 * Modèle d'XP « par palier » : chaque action définit une valeur d'XP <b>par palier</b>
 * (tableau aligné sur la section {@code tiers}). Toutes les sources sont toujours
 * « farmables » ; seule la quantité d'XP varie selon le palier du joueur.
 */
public class JobConfig {

    private static final Logger LOG = Logger.getLogger("Jobs");

    private final int    maxLevels;
    private final double xpBase;
    private final double xpFactor;
    private final double moneyBase;
    private final double moneyFactor;

    /** Paliers (bornes de niveau), dans l'ordre. L'index sert à indexer les tableaux d'XP. */
    private final List<Tier> tiers = new ArrayList<>();

    /** Map&lt;JobType, Map&lt;"MATERIAL" ou "MATERIAL:META", xp[par palier]&gt;&gt; (ordre YAML préservé). */
    private final Map<JobType, Map<String, int[]>> actionXp = new EnumMap<>(JobType.class);

    /** Map&lt;JobType, Map&lt;"break"/"place", Map&lt;"MATERIAL[:META]", xp[par palier]&gt;&gt;&gt;. */
    private final Map<JobType, Map<String, Map<String, int[]>>> farmerActionXp = new EnumMap<>(JobType.class);

    /** Map&lt;JobType, Map&lt;level, LevelReward&gt;&gt; */
    private final Map<JobType, Map<Integer, LevelReward>> rewardOverrides = new EnumMap<>(JobType.class);

    /** Libellés lisibles (FR) pour l'onglet Information : clé action (majuscules) → texte. */
    private final Map<String, String> sourceNames = new HashMap<>();

    /** Matériaux résultat exclus du gain d'XP Artisan (crafts trop simples). */
    private final Set<String> craftBlacklist = new HashSet<>();

    public JobConfig(JavaPlugin plugin) {
        FileConfiguration jobs = loadJobsConfig(plugin);

        this.maxLevels   = jobs.getInt("max-levels", 50);
        this.xpBase      = jobs.getDouble("xp-formula.base", 100);
        this.xpFactor    = jobs.getDouble("xp-formula.factor", 1.13);
        this.moneyBase   = jobs.getDouble("money-formula.base", 40);
        this.moneyFactor = jobs.getDouble("money-formula.factor", 1.10);

        // Paliers — doit être parsé avant les actions (les tableaux s'alignent dessus).
        parseTiers(jobs);

        // Parse actions per job (valeurs = tableau d'XP par palier, ou scalaire = identique partout)
        for (JobType jt : new JobType[]{JobType.MINER, JobType.FARMER, JobType.ARTISAN}) {
            String path = "jobs." + jt.name() + ".actions";
            ConfigurationSection sec = jobs.getConfigurationSection(path);
            if (sec == null) continue;

            if (jt == JobType.FARMER) {
                // Farmer : sections imbriquées "break" et "place"
                Map<String, Map<String, int[]>> farmerMap = new LinkedHashMap<>();
                for (String subKey : sec.getKeys(false)) {
                    ConfigurationSection subSec = sec.getConfigurationSection(subKey);
                    if (subSec != null) {
                        Map<String, int[]> entries = new LinkedHashMap<>();
                        for (String mat : subSec.getKeys(false)) {
                            entries.put(mat.toUpperCase(Locale.ROOT), readTierArray(subSec.get(mat)));
                        }
                        farmerMap.put(subKey.toLowerCase(Locale.ROOT), entries);
                    }
                }
                farmerActionXp.put(jt, farmerMap);
            } else {
                Map<String, int[]> map = new LinkedHashMap<>();
                for (String mat : sec.getKeys(false)) {
                    map.put(mat.toUpperCase(Locale.ROOT), readTierArray(sec.get(mat)));
                }
                actionXp.put(jt, map);
            }
        }

        // Libellés lisibles (optionnels)
        ConfigurationSection namesSec = jobs.getConfigurationSection("source-names");
        if (namesSec != null) {
            for (String k : namesSec.getKeys(false)) {
                sourceNames.put(k.toUpperCase(Locale.ROOT), namesSec.getString(k));
            }
        }

        // Parse craft blacklist for ARTISAN
        List<String> bl = jobs.getStringList("jobs.ARTISAN.craft-blacklist");
        for (String s : bl) craftBlacklist.add(s.toUpperCase(Locale.ROOT));

        // Parse reward overrides
        ConfigurationSection rewardsSec = jobs.getConfigurationSection("rewards");
        if (rewardsSec != null) {
            for (JobType jt : new JobType[]{JobType.MINER, JobType.FARMER, JobType.ARTISAN}) {
                ConfigurationSection jSec = rewardsSec.getConfigurationSection(jt.name());
                if (jSec == null) continue;
                Map<Integer, LevelReward> levelMap = new HashMap<>();
                for (String levelStr : jSec.getKeys(false)) {
                    try {
                        int level = Integer.parseInt(levelStr);
                        ConfigurationSection lSec = jSec.getConfigurationSection(levelStr);
                        if (lSec == null) continue;
                        long money = lSec.getLong("money", -1);
                        List<String> itemDefs = lSec.getStringList("items");
                        LevelReward reward = new LevelReward(money, parseItems(itemDefs));
                        levelMap.put(level, reward);
                    } catch (NumberFormatException ignored) {}
                }
                rewardOverrides.put(jt, levelMap);
            }
        }
    }

    private void parseTiers(FileConfiguration jobs) {
        List<Map<?, ?>> list = jobs.getMapList("tiers");
        if (list != null) {
            for (Map<?, ?> m : list) {
                Object nameObj = m.get("name");
                String name = nameObj != null ? String.valueOf(nameObj) : "Palier";
                int min = m.get("min") instanceof Number ? ((Number) m.get("min")).intValue() : 1;
                int max = m.get("max") instanceof Number ? ((Number) m.get("max")).intValue() : min;
                tiers.add(new Tier(name, min, max));
            }
        }
        if (tiers.isEmpty()) {
            // Paliers par défaut (alignés sur 50 niveaux)
            tiers.add(new Tier("Débutant",   1,  5));
            tiers.add(new Tier("Apprenti",   6,  10));
            tiers.add(new Tier("Confirmé",   11, 20));
            tiers.add(new Tier("Expert",     21, 30));
            tiers.add(new Tier("Maître",     31, 40));
            tiers.add(new Tier("Légendaire", 41, 50));
        }
    }

    /**
     * Lit une valeur de config (liste d'entiers ou scalaire) en un tableau d'XP de longueur
     * {@code tiers.size()}. Une liste plus courte est complétée par sa dernière valeur ;
     * un scalaire est répété sur tous les paliers.
     */
    private int[] readTierArray(Object raw) {
        int n = tiers.size();
        int[] arr = new int[n];
        if (raw instanceof List) {
            List<?> l = (List<?>) raw;
            int last = 0;
            for (int i = 0; i < n; i++) {
                if (i < l.size()) {
                    Object o = l.get(i);
                    if (o instanceof Number) last = ((Number) o).intValue();
                    else {
                        try { last = Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException ignored) {}
                    }
                }
                arr[i] = last; // au-delà de la liste : on conserve la dernière valeur (padding)
            }
        } else {
            int v = 0;
            if (raw instanceof Number) v = ((Number) raw).intValue();
            else if (raw != null) {
                try { v = Integer.parseInt(String.valueOf(raw)); } catch (NumberFormatException ignored) {}
            }
            Arrays.fill(arr, v);
        }
        return arr;
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public int getMaxLevels() { return maxLevels; }

    public int tierCount() { return tiers.size(); }

    public List<Tier> getTiers() { return Collections.unmodifiableList(tiers); }

    /** Index de palier (0-based) pour un niveau donné. Clampé aux bornes. */
    public int tierIndex(int level) {
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (level >= t.min && level <= t.max) return i;
        }
        if (level < tiers.get(0).min) return 0;
        return tiers.size() - 1;
    }

    /** XP requis pour atteindre `level` (à partir de 0). Level 1 = xpBase. */
    public int getXpRequired(int level) {
        if (level <= 0) return 0;
        return (int) Math.floor(xpBase * Math.pow(xpFactor, level - 1));
    }

    /** Argent donné au niveau `level` (surchargé si présent dans jobs.yml). */
    public long getMoneyReward(JobType job, int level) {
        Map<Integer, LevelReward> overrides = rewardOverrides.get(job);
        if (overrides != null) {
            LevelReward r = overrides.get(level);
            if (r != null && r.money >= 0) return r.money;
        }
        return (long) Math.floor(moneyBase * Math.pow(moneyFactor, level - 1));
    }

    /** Items récompensés au niveau `level` (peut être vide). */
    public List<ItemStack> getItemRewards(JobType job, int level) {
        Map<Integer, LevelReward> overrides = rewardOverrides.get(job);
        if (overrides != null) {
            LevelReward r = overrides.get(level);
            if (r != null) return r.items;
        }
        return Collections.emptyList();
    }

    /** Description texte des récompenses pour un niveau (utilisée côté client). */
    public String getRewardString(JobType job, int level) {
        long money = getMoneyReward(job, level);
        List<ItemStack> items = getItemRewards(job, level);
        StringBuilder sb = new StringBuilder();
        sb.append(formatMoney(money)).append(" $");
        for (ItemStack is : items) {
            if (is != null) {
                sb.append("|").append(is.getType().name()).append(":").append(is.getAmount());
            }
        }
        return sb.toString();
    }

    /** XP Mineur pour un matériau cassé, selon le palier du joueur (clé = "MAT" ou "MAT:META"). */
    public int getMinerXp(String materialKey, int level) {
        return lookup(actionXp.get(JobType.MINER), materialKey, level);
    }

    /** XP Artisan pour un type d'action ("craft", "brew", "enchant", "anvil"), selon le palier. */
    public int getArtisanXp(String action, int level) {
        return lookup(actionXp.get(JobType.ARTISAN), action, level);
    }

    /** XP Agriculteur pour une action ("break"/"place") et un matériau, selon le palier. */
    public int getFarmerXp(String action, String materialKey, int level) {
        Map<String, Map<String, int[]>> outer = farmerActionXp.get(JobType.FARMER);
        if (outer == null) return 0;
        return lookup(outer.get(action.toLowerCase(Locale.ROOT)), materialKey, level);
    }

    private int lookup(Map<String, int[]> map, String key, int level) {
        if (map == null) return 0;
        int[] arr = map.get(key.toUpperCase(Locale.ROOT));
        return arr == null ? 0 : arr[tierIndex(level)];
    }

    /** Retourne true si ce matériau est dans la blacklist des crafts simples. */
    public boolean isCraftBlacklisted(Material mat) {
        return craftBlacklist.contains(mat.name());
    }

    // ── Parsing items ─────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private List<ItemStack> parseItems(List<String> defs) {
        List<ItemStack> result = new ArrayList<>();
        for (String def : defs) {
            try {
                // Format: "MATERIAL:AMOUNT" ou "MATERIAL:META:AMOUNT"
                String[] parts = def.split(":");
                Material mat = null;
                int meta = 0, amount = 1;
                if (parts.length == 2) {
                    mat    = Material.getMaterial(parts[0].toUpperCase(Locale.ROOT));
                    amount = Integer.parseInt(parts[1]);
                } else if (parts.length == 3) {
                    mat    = Material.getMaterial(parts[0].toUpperCase(Locale.ROOT));
                    meta   = Integer.parseInt(parts[1]);
                    amount = Integer.parseInt(parts[2]);
                }
                if (mat != null) result.add(new ItemStack(mat, amount, (short) meta));
            } catch (Exception e) {
                LOG.warning("[Jobs] Impossible de parser l'item : " + def);
            }
        }
        return result;
    }

    // ── Sources d'XP (onglet Information) ───────────────────────────────────────

    /**
     * Sources d'XP à envoyer au client, dérivées des actions et déclinées par palier.
     * Chaque palier (catégorie) liste toutes les sources du métier avec l'XP correspondante.
     * {@code minLevel = 0} : rien n'est verrouillé (tout est farmable à chaque palier).
     */
    public List<XpSourceEntry> getXpSources(JobType job) {
        List<Src> sources = buildSources(job);
        List<XpSourceEntry> result = new ArrayList<>();
        for (int ti = 0; ti < tiers.size(); ti++) {
            String cat = tierLabel(ti);
            for (Src s : sources) {
                int xp = ti < s.xp.length ? s.xp[ti] : 0;
                result.add(new XpSourceEntry(cat, s.label, xp, 0));
            }
        }
        return result;
    }

    /** Liste des sources d'un métier, triée par XP croissante (valeur du palier I). */
    private List<Src> buildSources(JobType job) {
        List<Src> list = new ArrayList<>();
        if (job == JobType.FARMER) {
            Map<String, Map<String, int[]>> outer = farmerActionXp.get(job);
            if (outer != null) {
                collect(list, outer.get("break"), "Récolte : ", job);
                collect(list, outer.get("place"), "Plantation : ", job);
            }
        } else {
            collect(list, actionXp.get(job), "", job);
        }
        list.sort((a, b) -> Integer.compare(a.xp.length == 0 ? 0 : a.xp[0],
                                            b.xp.length == 0 ? 0 : b.xp[0]));
        return list;
    }

    private void collect(List<Src> out, Map<String, int[]> map, String prefix, JobType job) {
        if (map == null) return;
        for (Map.Entry<String, int[]> e : map.entrySet()) {
            out.add(new Src(prefix + sourceLabel(job, e.getKey()), e.getValue()));
        }
    }

    private static final class Src {
        final String label;
        final int[]  xp;
        Src(String label, int[] xp) { this.label = label; this.xp = xp; }
    }

    /** Libellé lisible d'une source : mapping {@code source-names} sinon repli automatique. */
    private String sourceLabel(JobType job, String key) {
        String mapped = sourceNames.get(key.toUpperCase(Locale.ROOT));
        if (mapped != null) return mapped;
        return job == JobType.ARTISAN ? prettyAction(key) : prettyKey(key);
    }

    /** Libellé de catégorie : "Palier III — Confirmé (Niv. 11-20)". */
    private String tierLabel(int idx) {
        Tier t = tiers.get(idx);
        return "Palier " + roman(idx + 1) + " — " + t.name + " (Niv. " + t.min + "-" + t.max + ")";
    }

    private static String roman(int n) {
        switch (n) {
            case 1: return "I";   case 2: return "II";  case 3: return "III";
            case 4: return "IV";  case 5: return "V";   case 6: return "VI";
            case 7: return "VII"; case 8: return "VIII"; case 9: return "IX"; case 10: return "X";
            default: return String.valueOf(n);
        }
    }

    private static String prettyKey(String key) {
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_:]");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i == 0) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            else        sb.append(' ').append(parts[i]);
        }
        return sb.toString();
    }

    private static String prettyAction(String action) {
        switch (action.toUpperCase(Locale.ROOT)) {
            case "CRAFT":   return "Craft";
            case "BREW":    return "Brassage";
            case "ENCHANT": return "Enchantement";
            case "ANVIL":   return "Enclume";
            case "SMELT":   return "Fonte";
            default:        return prettyKey(action);
        }
    }

    private String formatMoney(long v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000.0);
        return String.valueOf(v);
    }

    // ── Chargement fichier ──────────────────────────────────────────────────────

    private FileConfiguration loadJobsConfig(JavaPlugin plugin) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "jobs/jobs.yml");
        file.getParentFile().mkdirs();
        if (!file.exists()) plugin.saveResource("jobs/jobs.yml", false);

        // Lecture forcée en UTF-8 : Spigot 1.8 lit sinon avec le charset par défaut de la JVM
        // (Windows-1252 sous Windows), ce qui corrompt les accents (é → Ã©, — → â€").
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            String content = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            cfg.loadFromString(content);
            return cfg;
        } catch (Exception e) {
            LOG.warning("[Jobs] Lecture UTF-8 de jobs.yml échouée (" + e.getMessage()
                    + "), repli sur le chargement par défaut.");
            return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        }
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    public static class Tier {
        public final String name;
        public final int    min;
        public final int    max;
        public Tier(String name, int min, int max) {
            this.name = name;
            this.min  = min;
            this.max  = max;
        }
    }

    public static class LevelReward {
        public final long money;
        public final List<ItemStack> items;
        public LevelReward(long money, List<ItemStack> items) {
            this.money = money;
            this.items = items;
        }
    }

    public static class XpSourceEntry {
        public final String category;
        public final String label;
        public final int    xp;
        public final int    minLevel;

        public XpSourceEntry(String category, String label, int xp) {
            this(category, label, xp, 1);
        }

        public XpSourceEntry(String category, String label, int xp, int minLevel) {
            this.category = category;
            this.label    = label;
            this.xp       = xp;
            this.minLevel = minLevel;
        }
    }
}
