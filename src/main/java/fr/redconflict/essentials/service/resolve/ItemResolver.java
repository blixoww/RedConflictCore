package fr.redconflict.essentials.service.resolve;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Résolution des items pour /give : accepte {@code <nom>[:<data>]} ou
 * {@code <id>[:<data>]} (ex. {@code diamond_sword}, {@code 276}, {@code 35:14}).
 *
 * <p>Sert aussi la complétion : la liste des noms est construite depuis
 * {@link Material}, donc les items propres au serveur (RUBY_SWORD, COBALT_KEY…)
 * y figurent sans qu'on ait à tenir une seconde liste à jour.
 */
public class ItemResolver {

    /** Noms proposés en complétion, en minuscules et triés. Immuable. */
    private final List<String> names;

    public ItemResolver() {
        List<String> known = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material == Material.AIR) continue;
            known.add(material.name().toLowerCase(Locale.ROOT));
        }
        Collections.sort(known);
        this.names = Collections.unmodifiableList(known);
    }

    /**
     * @return un ItemStack de quantité 1, ou {@code null} si l'item est inconnu.
     */
    @SuppressWarnings("deprecation") // Material.getMaterial(int) : voulu, syntaxe id 1.8
    public ItemStack resolve(String token) {
        String namePart = token;
        short data = 0;
        int colon = token.indexOf(':');
        if (colon >= 0) {
            namePart = token.substring(0, colon);
            try {
                data = Short.parseShort(token.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Material material;
        try {
            material = Material.getMaterial(Integer.parseInt(namePart));
        } catch (NumberFormatException e) {
            material = Material.matchMaterial(namePart);
        }
        if (material == null || material == Material.AIR) {
            return null;
        }
        return new ItemStack(material, 1, data);
    }

    /**
     * Complétion d'un début de nom d'item.
     *
     * <p>Les préfixes d'abord ({@code diam} → {@code diamond_sword}…), puis les
     * correspondances internes ({@code sword} → {@code ruby_sword},
     * {@code cobalt_sword}…). Sans ce second passage, un item custom resterait
     * introuvable pour qui ne connaît pas son préfixe.
     *
     * @param token ce qui est déjà tapé (peut être vide : tout est proposé)
     */
    public List<String> suggest(String token) {
        // Une data déjà saisie (wool:14) : plus rien à proposer, on la laisse taper.
        if (token.indexOf(':') >= 0) return Collections.emptyList();

        String needle = token.toLowerCase(Locale.ROOT).replace('-', '_');
        if (needle.isEmpty()) return names;

        List<String> prefixed = new ArrayList<>();
        List<String> inner = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(needle)) prefixed.add(name);
            else if (name.contains(needle)) inner.add(name);
        }
        prefixed.addAll(inner);
        return prefixed;
    }
}
