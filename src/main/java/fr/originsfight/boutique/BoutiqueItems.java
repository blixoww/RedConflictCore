package fr.originsfight.boutique;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utilitaire interne du package boutique : construit les ItemStack remis aux joueurs
 * après achat (offre spéciale notamment).
 *
 * Plus de méthodes "glass / skull / lore-only" depuis que la GUI est passée côté
 * client — on garde uniquement le strict nécessaire au don d'item physique.
 */
public final class BoutiqueItems {

    private BoutiqueItems() {}

    public static ItemStack build(Material mat, int amount, short data, String name, List<String> lore) {
        ItemStack s = new ItemStack(mat, amount, data);
        ItemMeta m = s.getItemMeta();
        if (m == null) return s;
        if (name != null) m.setDisplayName(color(name));
        if (lore != null && !lore.isEmpty()) {
            List<String> l = new ArrayList<>(lore.size());
            for (String line : lore) l.add(color(line));
            m.setLore(l);
        }
        s.setItemMeta(m);
        return s;
    }

    public static ItemStack withEnchants(ItemStack base, Map<Enchantment, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) return base;
        ItemMeta m = base.getItemMeta();
        if (m == null) return base;
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            m.addEnchant(e.getKey(), e.getValue(), true);
        }
        base.setItemMeta(m);
        return base;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
