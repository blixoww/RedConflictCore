package fr.originsfight.job;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;
import java.util.TreeMap;

/**
 * Charge et expose la configuration du système de métiers depuis jobs.yml.
 */
public class JobConfig {

    private static final Logger LOG = Logger.getLogger("Jobs");

    private final int    maxLevels;
    private final double xpBase;
    private final double xpFactor;
    private final double moneyBase;
    private final double moneyFactor;

    /** Map<JobType, Map<"MATERIAL" or "MATERIAL:META", xp>> */
    private final Map<JobType, Map<String, Integer>> actionXp = new EnumMap<>(JobType.class);

    /** Map<JobType, Map<"break" or "place", Map<"MATERIAL:META" or "MATERIAL", xp>>> */
    private final Map<JobType, Map<String, Map<String, Integer>>> farmerActionXp = new EnumMap<>(JobType.class);

    /** Map<JobType, Map<level, LevelReward>> */
    private final Map<JobType, Map<Integer, LevelReward>> rewardOverrides = new EnumMap<>(JobType.class);

    /** Matériaux résultat exclus du gain d'XP Artisan (crafts trop simples). */
    private final Set<String> craftBlacklist = new HashSet<>();

    public JobConfig(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        // defaults are loaded by plugin.saveDefaultConfig() — we load jobs.yml separately
        FileConfiguration jobs = loadJobsConfig(plugin);

        this.maxLevels   = jobs.getInt("max-levels", 50);
        this.xpBase      = jobs.getDouble("xp-formula.base", 100);
        this.xpFactor    = jobs.getDouble("xp-formula.factor", 1.12);
        this.moneyBase   = jobs.getDouble("money-formula.base", 200);
        this.moneyFactor = jobs.getDouble("money-formula.factor", 1.18);

        // Parse actions per job
        for (JobType jt : new JobType[]{JobType.MINER, JobType.FARMER, JobType.ARTISAN}) {
            String path = "jobs." + jt.name() + ".actions";
            ConfigurationSection sec = jobs.getConfigurationSection(path);
            if (sec == null) continue;

            if (jt == JobType.FARMER) {
                // Farmer has nested "break" and "place" sections
                Map<String, Map<String, Integer>> farmerMap = new HashMap<>();
                for (String subKey : sec.getKeys(false)) {
                    ConfigurationSection subSec = sec.getConfigurationSection(subKey);
                    if (subSec != null) {
                        Map<String, Integer> entries = new HashMap<>();
                        for (String mat : subSec.getKeys(false)) {
                            entries.put(mat.toUpperCase(Locale.ROOT), subSec.getInt(mat));
                        }
                        farmerMap.put(subKey.toLowerCase(), entries);
                    }
                }
                farmerActionXp.put(jt, farmerMap);
            } else {
                Map<String, Integer> map = new HashMap<>();
                for (String mat : sec.getKeys(false)) {
                    map.put(mat.toUpperCase(Locale.ROOT), sec.getInt(mat));
                }
                actionXp.put(jt, map);
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

    private FileConfiguration loadJobsConfig(JavaPlugin plugin) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "jobs/jobs.yml");
        file.getParentFile().mkdirs();
        if (!file.exists()) plugin.saveResource("jobs/jobs.yml", false);
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public int getMaxLevels() { return maxLevels; }

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

    /** XP donnée au Mineur pour avoir cassé un matériau (clé = "MAT" ou "MAT:META"). */
    public int getMinerXp(String materialKey) {
        Map<String, Integer> map = actionXp.get(JobType.MINER);
        if (map == null) return 0;
        Integer v = map.get(materialKey.toUpperCase(Locale.ROOT));
        return v != null ? v : 0;
    }

    /** XP Artisan pour un type d'action ("craft", "brew", "enchant", "anvil"). */
    public int getArtisanXp(String action) {
        Map<String, Integer> map = actionXp.get(JobType.ARTISAN);
        if (map == null) return 0;
        Integer v = map.get(action.toUpperCase(Locale.ROOT));
        return v != null ? v : 0;
    }

    /** Retourne true si ce matériau est dans la blacklist des crafts simples. */
    public boolean isCraftBlacklisted(Material mat) {
        return craftBlacklist.contains(mat.name());
    }

    /** XP Agriculteur pour une action (type = "break" ou "place") et un matériau. */
    public int getFarmerXp(String action, String materialKey) {
        Map<String, Map<String, Integer>> outer = farmerActionXp.get(JobType.FARMER);
        if (outer == null) return 0;
        Map<String, Integer> inner = outer.get(action.toLowerCase(Locale.ROOT));
        if (inner == null) return 0;
        Integer v = inner.get(materialKey.toUpperCase(Locale.ROOT));
        return v != null ? v : 0;
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

    /** Sources d'XP à envoyer au client pour l'onglet Information. */
    public List<XpSourceEntry> getXpSources(JobType job) {
        List<XpSourceEntry> result = new ArrayList<>();
        if (job == JobType.MINER) {
            Map<String, Integer> map = actionXp.get(JobType.MINER);
            if (map != null) {
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
                entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Integer> e : entries)
                    result.add(new XpSourceEntry("Blocs", prettyKey(e.getKey()), e.getValue()));
            }
        } else if (job == JobType.FARMER) {
            Map<String, Map<String, Integer>> outer = farmerActionXp.get(JobType.FARMER);
            if (outer != null) {
                Map<String, Integer> breakMap = outer.get("break");
                if (breakMap != null) {
                    List<Map.Entry<String, Integer>> entries = new ArrayList<>(breakMap.entrySet());
                    entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    for (Map.Entry<String, Integer> e : entries)
                        result.add(new XpSourceEntry("Récolte", prettyKey(e.getKey()), e.getValue()));
                }
                Map<String, Integer> placeMap = outer.get("place");
                if (placeMap != null) {
                    List<Map.Entry<String, Integer>> entries = new ArrayList<>(placeMap.entrySet());
                    entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    for (Map.Entry<String, Integer> e : entries)
                        result.add(new XpSourceEntry("Plantation", prettyKey(e.getKey()), e.getValue()));
                }
            }
        } else if (job == JobType.ARTISAN) {
            Map<String, Integer> map = actionXp.get(JobType.ARTISAN);
            if (map != null) {
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
                entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Integer> e : entries)
                    result.add(new XpSourceEntry("Craft & fabrication", prettyAction(e.getKey()), e.getValue()));
            }
        }
        return result;
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

    // ── DTO ───────────────────────────────────────────────────────────────────

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
        public XpSourceEntry(String category, String label, int xp) {
            this.category = category;
            this.label    = label;
            this.xp       = xp;
        }
    }
}

