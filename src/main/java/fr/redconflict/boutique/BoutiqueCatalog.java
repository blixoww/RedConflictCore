package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lecture normalisée de {@code boutique/boutique.yml}.
 *
 * <p><b>Le YAML reste la seule source du catalogue.</b> La boutique en jeu, la
 * boutique du site et le verrou d'appartenance lisent tous cette classe : changer
 * un prix ou ajouter un grade se fait à un seul endroit, et les deux vitrines ne
 * peuvent pas afficher des choses différentes.
 *
 * <p>Le catalogue est relu à chaque {@link #reload()} — donc au démarrage et sur
 * {@code /red reload} — puis réexporté vers la base du site.
 */
public final class BoutiqueCatalog {

    /** Chemins YAML des catégories, dans l'ordre d'affichage. */
    private static final String[][] SECTIONS = {
            { "boutique.grades",    "grade"   },
            { "boutique.commandes", "cmd"     },
            { "boutique.kits",      "kit"     },
            { "boutique.spawners",  "spawner" },
            { "boutique.packs",     "pack"    },
    };

    private final RedConflictCore plugin;

    /** clé = "categorie:id" (id en minuscules), pour une résolution directe. */
    private Map<String, BoutiqueItem> byKey = Collections.emptyMap();
    private List<BoutiqueItem> ordered = Collections.emptyList();

    public BoutiqueCatalog(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    // ── Chargement ─────────────────────────────────────────────────────────────

    public void reload() {
        FileConfiguration cfg = plugin.getBoutiqueConfig();
        Map<String, BoutiqueItem> keyed = new LinkedHashMap<>();
        List<BoutiqueItem> list = new ArrayList<>();

        if (cfg != null) {
            for (String[] section : SECTIONS) {
                List<?> entries = cfg.getList(section[0]);
                if (entries == null) continue;
                for (Object raw : entries) {
                    if (!(raw instanceof Map)) continue;
                    BoutiqueItem item = parse(section[1], asStringMap(raw));
                    if (item == null) continue;
                    list.add(item);
                    keyed.put(key(item.category, item.id), item);
                }
            }
        }

        this.ordered = Collections.unmodifiableList(list);
        this.byKey = Collections.unmodifiableMap(keyed);
        plugin.getLogger().info("[Boutique] Catalogue chargé : " + list.size() + " articles.");
    }

    private BoutiqueItem parse(String category, Map<String, Object> m) {
        String id = str(m.get("id"));
        if (id.isEmpty()) return null;

        List<String> description = new ArrayList<>();
        Object desc = m.get("description");
        if (desc instanceof List) {
            for (Object line : (List<?>) desc) description.add(str(line));
        }

        // Icône : les spawners n'en déclarent pas, ils portent un "mob".
        String icon = str(m.get("icone"));
        if (icon.isEmpty()) icon = str(m.get("mob"));

        return new BoutiqueItem(
                category,
                id,
                str(m.get("nom")),
                icon.toUpperCase(Locale.ROOT),
                description,
                asInt(m.get("prix_pb")),
                asInt(m.get("prix_pb_perm")),
                asLong(m.get("prix_monnaie")),
                asLong(m.get("prix_monnaie_perm")),
                asLong(m.get("duree")),
                extractNodes(m),
                commandList(m, "commandes", "commande"),
                commandList(m, "commandes_perm", "commande_perm"));
    }

    /**
     * Ramène au pluriel la clé que le YAML écrit tantôt au singulier
     * ({@code commande}, pour une commande) tantôt au pluriel
     * ({@code commandes}, pour un grade qui pose plusieurs nœuds).
     */
    private static List<String> commandList(Map<String, Object> m, String pluralKey, String singularKey) {
        List<String> out = new ArrayList<>();
        Object plural = m.get(pluralKey);
        if (plural instanceof List) {
            for (Object line : (List<?>) plural) out.add(str(line));
            return out;
        }
        Object singular = m.get(singularKey);
        if (singular != null) out.add(str(singular));
        return out;
    }

    /**
     * Extrait les nœuds de permission accordés à vie par l'article.
     *
     * <p>On lit les commandes <b>permanentes</b> ({@code commandes_perm} /
     * {@code commande_perm}) : ce sont elles qui décrivent le droit dans l'absolu,
     * sans la durée. Un article sans version permanente retombe sur les commandes
     * temporaires, dont on ignore simplement le suffixe de durée.
     *
     * <p>Seules les lignes {@code lp user ... permission set[temp] <nœud> true}
     * comptent. Un {@code give}, un {@code mspa} ou un {@code givekey} ne donne
     * aucun droit : c'est ce qui fait qu'un spawner reste rachetable et qu'un
     * grade ne l'est pas, sans avoir à le déclarer nulle part.
     */
    private static Set<String> extractNodes(Map<String, Object> m) {
        Set<String> nodes = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();

        Object perm = m.containsKey("commandes_perm") ? m.get("commandes_perm") : m.get("commande_perm");
        Object temp = m.containsKey("commandes") ? m.get("commandes") : m.get("commande");
        Object chosen = perm != null ? perm : temp;

        if (chosen instanceof List) {
            for (Object line : (List<?>) chosen) lines.add(str(line));
        } else if (chosen != null) {
            lines.add(str(chosen));
        }

        for (String line : lines) {
            String node = permissionNode(line);
            if (node != null) nodes.add(node);
        }
        return nodes;
    }

    /**
     * Renvoie le nœud d'une ligne {@code lp user <cible> permission set|settemp
     * <nœud> true [...]}, ou {@code null} si la ligne n'accorde pas de permission.
     *
     * <p>Un {@code false} explicite est ignoré : retirer un droit n'est pas en
     * posséder un.
     */
    static String permissionNode(String line) {
        if (line == null) return null;
        String[] t = line.trim().split("\\s+");
        for (int i = 0; i + 2 < t.length; i++) {
            if (!"permission".equalsIgnoreCase(t[i])) continue;
            String verb = t[i + 1].toLowerCase(Locale.ROOT);
            if (!"set".equals(verb) && !"settemp".equals(verb)) continue;
            String node = t[i + 2];
            // La valeur est facultative dans LuckPerms et vaut true par défaut.
            if (i + 3 < t.length && "false".equalsIgnoreCase(t[i + 3])) return null;
            return node;
        }
        return null;
    }

    // ── Accès ──────────────────────────────────────────────────────────────────

    /** Tous les articles, dans l'ordre du fichier. */
    public List<BoutiqueItem> all() {
        return ordered;
    }

    /** {@code null} si l'article n'existe pas. */
    public BoutiqueItem find(String category, String id) {
        return byKey.get(key(category, id));
    }

    /** Articles qui accordent des droits — ceux que le verrou d'appartenance couvre. */
    public List<BoutiqueItem> ownable() {
        List<BoutiqueItem> out = new ArrayList<>();
        for (BoutiqueItem item : ordered) {
            if (item.isOwnable()) out.add(item);
        }
        return out;
    }

    private static String key(String category, String id) {
        return category + ':' + (id == null ? "" : id.toLowerCase(Locale.ROOT));
    }

    // ── Conversions YAML ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o).trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(str(o).trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
