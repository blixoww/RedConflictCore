package fr.redconflict.succes;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Catalogue des succès, lu une fois depuis {@code succes/succes.yml}.
 *
 * <p><b>Matériaux inconnus.</b> Le serveur est un fork de Spigot 1.8.8 avec des
 * blocs maison (rubis, cobalt, IDs 198-212). Un succès qui les vise ne peut pas
 * exister sur un serveur qui ne les a pas — le Minage, par exemple. Plutôt que
 * de planter au démarrage, une entrée dont le matériau est introuvable est
 * <b>ignorée avec un avertissement</b> : le reste du catalogue vit sa vie, et
 * le même fichier sert sur toutes les machines de la grappe.
 */
public final class SuccesCatalog {

    private static final Logger LOG = Logger.getLogger("Succes");

    /** Ordre du fichier préservé : c'est l'ordre d'affichage dans le menu. */
    private final Map<String, Succes> byId = new LinkedHashMap<>();
    private final List<String> categories = new ArrayList<>();

    public SuccesCatalog(JavaPlugin plugin) {
        FileConfiguration config = load(plugin);
        ConfigurationSection root = config.getConfigurationSection("succes");
        if (root == null) {
            LOG.warning("[Succes] succes.yml ne contient aucune section « succes » — catalogue vide.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            Succes succes = parse(id, section);
            if (succes != null) {
                byId.put(succes.id, succes);
                if (!categories.contains(succes.category)) categories.add(succes.category);
            }
        }
        if (byId.isEmpty()) {
            // Un catalogue vide n'est jamais voulu : sans ce cri, le serveur
            // démarre « normalement » et le joueur découvre un menu vide.
            LOG.severe("[Succes] AUCUN succès chargé — le menu s'ouvrira vide."
                    + " Vérifiez plugins/RedConflictCore/succes/succes.yml.");
        } else {
            LOG.info("[Succes] " + byId.size() + " succès chargés dans "
                    + categories.size() + " rubriques.");
        }
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    public Succes get(String id) {
        return byId.get(id);
    }

    /** Tous les succès, dans l'ordre du fichier. */
    public List<Succes> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public List<String> categories() {
        return Collections.unmodifiableList(categories);
    }

    public int size() {
        return byId.size();
    }

    /** Les succès sensibles à cet événement — en pratique une poignée sur des dizaines. */
    public List<Succes> matching(SuccesTrigger trigger, String target) {
        List<Succes> result = new ArrayList<>(2);
        for (Succes succes : byId.values()) {
            if (succes.matches(trigger, target)) result.add(succes);
        }
        return result;
    }

    // ── Analyse d'une entrée ─────────────────────────────────────────────────

    private Succes parse(String id, ConfigurationSection section) {
        SuccesTrigger trigger = SuccesTrigger.parse(section.getString("trigger"));
        if (trigger == null) {
            LOG.warning("[Succes] « " + id + " » ignoré : déclencheur inconnu ("
                    + section.getString("trigger") + ").");
            return null;
        }

        String target = section.getString("target", "");
        if (trigger.needsTarget()) {
            if (target.isEmpty()) {
                LOG.warning("[Succes] « " + id + " » ignoré : " + trigger + " exige une cible.");
                return null;
            }
            // Les cibles « matériau » doivent exister sur CE serveur (voir l'en-tête).
            if (trigger != SuccesTrigger.JOB_LEVEL && Material.getMaterial(target.toUpperCase(Locale.ROOT)) == null) {
                LOG.warning("[Succes] « " + id + " » ignoré : matériau « " + target
                        + " » absent de ce serveur.");
                return null;
            }
            target = target.toUpperCase(Locale.ROOT);
        }

        int goal = section.getInt("goal", 1);
        long money = section.getLong("reward.money", 0L);
        List<ItemStack> items = parseItems(id, section.getStringList("reward.items"));

        return new Succes(
                id,
                section.getString("name", id),
                section.getString("description", ""),
                section.getString("category", "DIVERS").toUpperCase(Locale.ROOT),
                section.getInt("tier", 0),
                trigger,
                target,
                goal,
                money,
                items,
                buildRewardText(money, items));
    }

    /**
     * Analyse une liste d'items.
     *
     * <p>Format : {@code MATERIAL:quantité:data|ENCHANT=niveau|ENCHANT=niveau}.
     * Quantité, data et enchantements sont facultatifs — {@code DIAMOND} suffit.
     * Exemple : {@code DIAMOND_SWORD:1|DAMAGE_ALL=5|DURABILITY=3}.
     */
    @SuppressWarnings("deprecation")
    private List<ItemStack> parseItems(String id, List<String> specs) {
        List<ItemStack> items = new ArrayList<>();
        for (String spec : specs) {
            if (spec == null || spec.trim().isEmpty()) continue;
            String[] blocks = spec.split("\\|");
            String[] head = blocks[0].trim().split(":");

            Material material = Material.getMaterial(head[0].trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                LOG.warning("[Succes] « " + id + " » : matériau de récompense inconnu (" + head[0] + "), ignoré.");
                continue;
            }

            int amount = 1;
            short data = 0;
            try {
                if (head.length >= 2) amount = Integer.parseInt(head[1].trim());
                if (head.length >= 3) data = Short.parseShort(head[2].trim());
            } catch (NumberFormatException e) {
                LOG.warning("[Succes] « " + id + " » : quantité ou data illisible dans « " + spec + " ».");
                continue;
            }

            ItemStack item = new ItemStack(material, Math.max(1, amount), data);
            for (int i = 1; i < blocks.length; i++) {
                applyEnchant(id, item, blocks[i].trim());
            }
            items.add(item);
        }
        return items;
    }

    private void applyEnchant(String id, ItemStack item, String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            LOG.warning("[Succes] « " + id + " » : enchantement mal formé (" + token + ").");
            return;
        }
        Enchantment enchantment = Enchantment.getByName(token.substring(0, eq).trim().toUpperCase(Locale.ROOT));
        if (enchantment == null) {
            LOG.warning("[Succes] « " + id + " » : enchantement inconnu (" + token + ").");
            return;
        }
        int level;
        try {
            level = Integer.parseInt(token.substring(eq + 1).trim());
        } catch (NumberFormatException e) {
            LOG.warning("[Succes] « " + id + " » : niveau d'enchantement illisible (" + token + ").");
            return;
        }
        // Unsafe : les récompenses dépassent volontairement les paliers vanilla
        // (Sharpness V sur une épée, Efficiency VI sur une pioche).
        item.addUnsafeEnchantment(enchantment, level);
    }

    /** Résumé affiché côté client, dans la langue du serveur. */
    private String buildRewardText(long money, List<ItemStack> items) {
        List<String> parts = new ArrayList<>();
        if (money > 0) parts.add("§6" + formatMoney(money) + " $");
        for (ItemStack item : items) {
            String label = "§b" + item.getAmount() + "× " + prettyName(item);
            if (!item.getEnchantments().isEmpty()) label += " §d✦";
            parts.add(label);
        }
        if (parts.isEmpty()) return "§7Aucune récompense";
        return joinWith(parts, " §8· ");
    }

    private String prettyName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String joinWith(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(part);
        }
        return sb.toString();
    }

    /** Séparateur de milliers fine espace, comme le reste des montants du plugin. */
    static String formatMoney(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + sb;
    }

    // ── Chargement fichier ───────────────────────────────────────────────────

    /**
     * Catalogue du serveur : le fichier du dossier de données, sinon celui
     * embarqué dans le jar.
     *
     * <p><b>Pourquoi un repli sur le jar.</b> {@code saveResource} n'échoue pas
     * bruyamment : Bukkit attrape l'{@code IOException} et se contente de la
     * journaliser. Si la copie vers
     * {@code plugins/RedConflictCore/succes/succes.yml} ne se fait pas — droits,
     * disque plein, dossier de données monté en lecture seule — le fichier
     * n'existe pas, l'ancienne version se rabattait sur un
     * {@code YamlConfiguration} vide, et le module démarrait <b>sans erreur avec
     * zéro succès</b> : le menu s'ouvrait vide, sans rien indiquer. Lire le
     * catalogue du jar retire ce mode de panne silencieux — le serveur marche,
     * et la seule chose perdue est la personnalisation locale.
     *
     * <p>Lecture forcée en UTF-8 : Spigot 1.8 lit sinon avec le charset par
     * défaut de la JVM (Windows-1252 sous Windows), ce qui corrompt les accents.
     * Même précaution que {@code JobConfig}.
     */
    private FileConfiguration load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "succes/succes.yml");
        try {
            file.getParentFile().mkdirs();
            if (!file.exists()) plugin.saveResource("succes/succes.yml", false);
        } catch (Exception e) {
            LOG.warning("[Succes] Extraction de succes.yml impossible (" + e.getMessage() + ").");
        }

        FileConfiguration onDisk = parseYaml(readFile(file), "plugins/RedConflictCore/succes/succes.yml");
        if (onDisk != null) return onDisk;

        LOG.warning("[Succes] " + file.getPath() + " absent ou illisible —"
                + " lecture du catalogue embarqué dans le jar. Les modifications"
                + " locales du fichier ne seront PAS prises en compte.");

        FileConfiguration inJar = parseYaml(readResource(plugin, "succes/succes.yml"), "succes.yml (jar)");
        if (inJar != null) return inJar;

        LOG.severe("[Succes] Aucun catalogue lisible, ni sur disque ni dans le jar :"
                + " le menu des succès s'ouvrira vide.");
        return new YamlConfiguration();
    }

    /** Contenu UTF-8 du fichier, ou {@code null} s'il est absent, vide ou illisible. */
    private String readFile(File file) {
        if (!file.isFile() || file.length() == 0L) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warning("[Succes] Lecture de " + file.getPath() + " échouée : " + e.getMessage());
            return null;
        }
    }

    /** Contenu UTF-8 d'une ressource du jar, ou {@code null}. */
    private String readResource(JavaPlugin plugin, String path) {
        InputStream in = plugin.getResource(path);
        if (in == null) {
            LOG.severe("[Succes] Ressource « " + path + " » absente du jar —"
                    + " le jar déployé est-il bien celui qui embarque les succès ?");
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warning("[Succes] Lecture de la ressource " + path + " échouée : " + e.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // Rien à sauver d'une fermeture ratée sur un flux déjà lu.
            }
        }
    }

    /** @return la configuration analysée, ou {@code null} si le texte est nul ou invalide. */
    private FileConfiguration parseYaml(String text, String origin) {
        if (text == null) return null;
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(text);
            return config;
        } catch (Exception e) {
            LOG.severe("[Succes] YAML invalide dans " + origin + " : " + e.getMessage());
            return null;
        }
    }

}
