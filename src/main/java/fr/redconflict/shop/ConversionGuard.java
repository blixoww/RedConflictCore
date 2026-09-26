package fr.redconflict.shop;

import fr.redconflict.shop.ShopDatabase.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.logging.Logger;

/**
 * Garde-fou anti-arbitrage <b>entre items</b> : acheter des ingrédients à la
 * bourse, les transformer, revendre le produit plus cher.
 *
 * <p>Les conversions sont lues sur le serveur lui-même au démarrage — toutes
 * les recettes d'établi et de four enregistrées, items custom du fork compris
 * — plus une table des drops de blocs (minerais avec Fortune III, bibliothèque,
 * glowstone…) qu'aucune API n'expose. Une recette ajoutée demain est donc
 * couverte sans toucher à ce fichier.
 *
 * <p><b>La règle</b>, même principe que {@link ShopEventManager#sellCeiling} :
 * pour chaque conversion dont tous les ingrédients sont en bourse, la revente
 * du produit ne dépasse pas {@code marge} × le coût MINIMAL des ingrédients
 * (plancher d'achat des events et collier du marchand compris), divisé par le
 * rendement. Aucune chaîne achat → craft → revente n'est rentable, à aucun
 * moment. Le catalogue est réglé pour que cette borne ne morde pas sur les prix
 * normaux : elle ne sert qu'à rattraper la dérive quotidienne et les events.
 *
 * <p>Recalculé à chaque changement des prix d'achat (démarrage, régression
 * quotidienne) : le résultat est une table immuable, lue sans verrou.
 */
final class ConversionGuard {

    private static final Logger LOG = Logger.getLogger("Shop");

    /** Rendement moyen de Fortune III (x2,2) majoré de l'anneau de fortune (+15 %). */
    private static final double FORTUNE = 2.2 * 1.15;
    private static final double RING = 1.15;

    /** Joker de métadonnée des recettes (« n'importe quelle couleur de laine »). */
    static final int ANY = Short.MAX_VALUE;

    /** Une conversion : ingrédients (clé → quantité) → produit (clé, rendement). */
    static final class Conversion {
        final Map<String, Double> inputs;
        final String output;
        final double yield;

        Conversion(Map<String, Double> inputs, String output, double yield) {
            this.inputs = inputs;
            this.output = output;
            this.yield = yield;
        }
    }

    private final List<Conversion> conversions;
    private volatile Map<Integer, Long> ceilings = Collections.emptyMap();

    ConversionGuard(List<Conversion> conversions) {
        this.conversions = conversions;
    }

    /** Plafond de revente (centimes, collier non compris) imposé par les conversions, ou -1. */
    long ceiling(int itemId) {
        Long c = ceilings.get(itemId);
        return c == null ? -1L : c;
    }

    int size() {
        return conversions.size();
    }

    // ── Construction ─────────────────────────────────────────────────────────

    /** Lit les recettes enregistrées sur le serveur + la table des drops. */
    static ConversionGuard fromServer() {
        List<Conversion> out = new ArrayList<Conversion>();
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r;
            try {
                r = it.next();
            } catch (Throwable t) {
                continue;   // recette spéciale (feux d'artifice, teinture d'armure…)
            }
            if (r == null || r.getResult() == null || r.getResult().getType() == Material.AIR) continue;

            Map<String, Double> in = new HashMap<String, Double>();
            if (r instanceof ShapedRecipe) {
                ShapedRecipe s = (ShapedRecipe) r;
                Map<Character, ItemStack> map = s.getIngredientMap();
                for (String row : s.getShape()) {
                    for (char ch : row.toCharArray()) add(in, map.get(ch));
                }
            } else if (r instanceof ShapelessRecipe) {
                for (ItemStack s : ((ShapelessRecipe) r).getIngredientList()) add(in, s);
            } else if (r instanceof FurnaceRecipe) {
                add(in, ((FurnaceRecipe) r).getInput());
            } else {
                continue;
            }
            if (in.isEmpty()) continue;

            ItemStack res = r.getResult();
            String key = key(res.getType(), res.getDurability());
            if (in.containsKey(key)) continue;          // réparation, clonage…
            out.add(new Conversion(in, key, Math.max(1, res.getAmount())));
        }
        addDrops(out);
        LOG.info("[Shop] Garde anti-arbitrage : " + out.size() + " conversions (recettes + drops de blocs).");
        return new ConversionGuard(out);
    }

    /**
     * Drops de blocs : ce que rend un bloc acheté puis cassé. Rendements
     * MOYENS avec Fortune III et l'anneau de fortune — le pire cas réaliste
     * pour une boucle répétée.
     */
    private static void addDrops(List<Conversion> out) {
        drop(out, "COAL_ORE", 0, "COAL", 0, FORTUNE);
        drop(out, "DIAMOND_ORE", 0, "DIAMOND", 0, FORTUNE);
        drop(out, "EMERALD_ORE", 0, "EMERALD", 0, FORTUNE);
        drop(out, "QUARTZ_ORE", 0, "QUARTZ", 0, FORTUNE);
        drop(out, "RUBY_ORE", 0, "RUBY", 0, FORTUNE);
        drop(out, "COBALT_ORE", 0, "COBALT_INGOT", 0, FORTUNE);
        drop(out, "LAPIS_ORE", 0, "INK_SACK", 4, 6 * FORTUNE);
        drop(out, "REDSTONE_ORE", 0, "REDSTONE", 0, 6 * RING);
        drop(out, "GLOWSTONE", 0, "GLOWSTONE_DUST", 0, 4 * RING);
        drop(out, "MELON_BLOCK", 0, "MELON", 0, 6.4 * RING);
        drop(out, "SEA_LANTERN", 0, "PRISMARINE_CRYSTALS", 0, 4.3 * RING);
        drop(out, "BOOKSHELF", 0, "BOOK", 0, 3 * RING);
        drop(out, "CLAY", 0, "CLAY_BALL", 0, 4 * RING);
        drop(out, "SNOW_BLOCK", 0, "SNOW_BALL", 0, 4 * RING);
        drop(out, "GRAVEL", 0, "FLINT", 0, RING);
        drop(out, "STONE", 0, "COBBLESTONE", 0, RING);
        drop(out, "ENDER_CHEST", 0, "OBSIDIAN", 0, 8 * RING);
        drop(out, "WEB", 0, "STRING", 0, RING);
        drop(out, "GRASS", 0, "DIRT", 0, RING);
        drop(out, "MYCEL", 0, "DIRT", 0, RING);
    }

    private static void drop(List<Conversion> out, String from, int fromMeta,
                             String to, int toMeta, double yield) {
        Material a = Material.getMaterial(from);
        Material b = Material.getMaterial(to);
        if (a == null || b == null) return;              // matériau absent de ce serveur
        Map<String, Double> in = new HashMap<String, Double>();
        in.put(key(a, fromMeta), 1.0);
        out.add(new Conversion(in, key(b, toMeta), yield));
    }

    private static void add(Map<String, Double> in, ItemStack s) {
        if (s == null || s.getType() == Material.AIR) return;
        int meta = s.getDurability() & 0xFFFF;
        String k = key(s.getType(), meta == 0xFFFF ? ANY : meta);
        Double prev = in.get(k);
        in.put(k, (prev == null ? 0.0 : prev) + 1.0);
    }

    static String key(Material m, int meta) {
        return m.name() + ":" + meta;
    }

    // ── Calcul ───────────────────────────────────────────────────────────────

    /**
     * @param index  item de bourse par clé « MATERIAL:meta »
     * @param minBuy achat le plus bas atteignable d'un item (centimes, collier compris)
     * @param factor marge ÷ bonus de revente du collier
     */
    void recompute(Map<String, ShopItem> index, ToDoubleFunction<ShopItem> minBuy, double factor) {
        // Variante la moins chère de chaque matériau, pour les jokers des recettes.
        Map<String, ShopItem> cheapestAny = new HashMap<String, ShopItem>();
        for (Map.Entry<String, ShopItem> e : index.entrySet()) {
            String mat = e.getKey().substring(0, e.getKey().indexOf(':'));
            ShopItem cur = cheapestAny.get(mat);
            if (cur == null || minBuy.applyAsDouble(e.getValue()) < minBuy.applyAsDouble(cur)) {
                cheapestAny.put(mat, e.getValue());
            }
        }

        Map<Integer, Long> result = new HashMap<Integer, Long>();
        for (Conversion c : conversions) {
            ShopItem product = index.get(c.output);
            if (product == null || product.currentSellPrice <= 0) continue;
            double cost = 0.0;
            boolean buyable = true;
            for (Map.Entry<String, Double> in : c.inputs.entrySet()) {
                ShopItem ing = resolve(in.getKey(), index, cheapestAny);
                if (ing == null || ing.frozen) {
                    buyable = false;             // un ingrédient hors bourse : rien à arbitrer
                    break;
                }
                cost += minBuy.applyAsDouble(ing) * in.getValue();
            }
            if (!buyable) continue;
            long cap = (long) Math.floor(cost * factor / c.yield);
            Long prev = result.get(product.id);
            if (prev == null || cap < prev) result.put(product.id, cap);
        }
        this.ceilings = result;
    }

    private static ShopItem resolve(String key, Map<String, ShopItem> index,
                                    Map<String, ShopItem> cheapestAny) {
        if (key.endsWith(":" + ANY)) return cheapestAny.get(key.substring(0, key.indexOf(':')));
        return index.get(key);
    }
}
