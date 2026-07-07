package fr.originsfight.bottlexp;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * Création et lecture des bouteilles d'XP custom. Le nombre de niveaux est
 * porté par le lore (pas de NBT custom en 1.8) : une ligne-tag identifie la
 * bouteille, la ligne « Contient : N niveaux » porte la valeur.
 */
public final class BottleXpItem {

    private static final String DISPLAY_PREFIX = ChatColor.GREEN + "" + ChatColor.BOLD + "Bouteille d'XP";
    private static final String LORE_TAG = ChatColor.DARK_GRAY + "[BottleXP]";

    private BottleXpItem() {
    }

    public static ItemStack createBottle(int levels) {
        String plural = levels > 1 ? "x" : "";
        ItemStack item = new ItemStack(Material.EXP_BOTTLE, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(DISPLAY_PREFIX + ChatColor.RESET + ChatColor.GRAY + " — "
                + ChatColor.GOLD + levels + " niveau" + plural);
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Contient : " + ChatColor.GOLD + "" + ChatColor.BOLD + levels + " niveau" + plural,
                ChatColor.GRAY + "Clic droit pour récupérer l'XP.",
                LORE_TAG));
        item.setItemMeta(meta);
        return item;
    }

    /** @return les niveaux stockés, ou -1 si l'item n'est pas une bouteille d'XP custom. */
    public static int getLevels(ItemStack item) {
        if (item == null || item.getType() != Material.EXP_BOTTLE || !item.hasItemMeta()) {
            return -1;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName() || !meta.hasLore()
                || meta.getLore().stream().noneMatch(LORE_TAG::equals)) {
            return -1;
        }
        for (String line : meta.getLore()) {
            String stripped = ChatColor.stripColor(line);
            if (stripped.startsWith("Contient : ")) {
                try {
                    return Integer.parseInt(stripped.split(" ")[2]);
                } catch (RuntimeException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
