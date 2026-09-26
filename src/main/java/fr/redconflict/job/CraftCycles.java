package fr.redconflict.job;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Matériaux qu'on peut crafter puis décrafter en boucle : lingots ↔ blocs,
 * blé ↔ botte de foin, dalles ↔ blocs décoratifs…
 *
 * <p><b>L'exploit.</b> L'XP Artisan est versée par objet fabriqué. Neuf lingots
 * donnent un bloc, le bloc redonne neuf lingots : aucun matériau consommé, dix
 * objets fabriqués par aller-retour, et le métier montait jusqu'au niveau 50
 * (et ses ~110 000 $ de paliers) sans rien dépenser.
 *
 * <p>Calculé une fois au démarrage sur les recettes réellement enregistrées
 * (items custom compris) : composantes fortement connexes du graphe
 * « ingrédient → produit ». Tout produit qui appartient à un cycle ne rapporte
 * pas d'XP de craft.
 */
final class CraftCycles {

    private final Set<Material> cyclic;

    private CraftCycles(Set<Material> cyclic) {
        this.cyclic = cyclic;
    }

    boolean contains(Material m) {
        return cyclic.contains(m);
    }

    int size() {
        return cyclic.size();
    }

    static CraftCycles fromServer() {
        Map<Material, Set<Material>> edges = new EnumMap<Material, Set<Material>>(Material.class);
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r;
            try {
                r = it.next();
            } catch (Throwable t) {
                continue;
            }
            if (r == null || r.getResult() == null) continue;
            Material out = r.getResult().getType();
            Iterable<ItemStack> inputs;
            if (r instanceof ShapedRecipe) {
                inputs = ((ShapedRecipe) r).getIngredientMap().values();
            } else if (r instanceof ShapelessRecipe) {
                inputs = ((ShapelessRecipe) r).getIngredientList();
            } else {
                continue;   // le four ne se « décuit » pas : pas de cycle possible
            }
            for (ItemStack in : inputs) {
                if (in == null || in.getType() == Material.AIR || in.getType() == out) continue;
                Set<Material> next = edges.get(in.getType());
                if (next == null) {
                    next = EnumSet.noneOf(Material.class);
                    edges.put(in.getType(), next);
                }
                next.add(out);
            }
        }
        return new CraftCycles(Collections.unmodifiableSet(stronglyConnected(edges)));
    }

    /** Tarjan itératif : les nœuds des composantes de taille ≥ 2. */
    private static Set<Material> stronglyConnected(Map<Material, Set<Material>> g) {
        Map<Material, Integer> index = new HashMap<Material, Integer>();
        Map<Material, Integer> low = new HashMap<Material, Integer>();
        Deque<Material> stack = new ArrayDeque<Material>();
        Set<Material> onStack = EnumSet.noneOf(Material.class);
        Set<Material> result = EnumSet.noneOf(Material.class);
        int[] counter = {0};

        for (Material start : g.keySet()) {
            if (index.containsKey(start)) continue;
            Deque<Object[]> work = new ArrayDeque<Object[]>();
            work.push(new Object[] {start, successors(g, start).iterator()});
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.push(start);
            onStack.add(start);

            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                Material v = (Material) frame[0];
                @SuppressWarnings("unchecked")
                Iterator<Material> succ = (Iterator<Material>) frame[1];
                if (succ.hasNext()) {
                    Material w = succ.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]++);
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Object[] {w, successors(g, w).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    Material parent = (Material) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    Set<Material> comp = EnumSet.noneOf(Material.class);
                    Material w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        comp.add(w);
                    } while (w != v);
                    if (comp.size() > 1) result.addAll(comp);
                }
            }
        }
        return result;
    }

    private static Set<Material> successors(Map<Material, Set<Material>> g, Material m) {
        Set<Material> s = g.get(m);
        return s == null ? Collections.<Material>emptySet() : s;
    }
}
