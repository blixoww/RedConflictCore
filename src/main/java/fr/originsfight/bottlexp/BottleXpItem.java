package fr.originsfight.bottlexp;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * Utilitaire pour créer et lire les bouteilles d'XP custom.
 */
public class BottleXpItem {

    // Tag NBT simulé via le nom et lore — identifiable de façon unique
    private static final String DISPLAY_PREFIX = ChatColor.GREEN + "" + ChatColor.BOLD + "Bouteille d'XP";
    private static final String LORE_TAG = ChatColor.DARK_GRAY + "[BottleXP]";

    /**
     * Crée une bouteille d'XP contenant le nombre de niveaux indiqué.
     */
    public static ItemStack createBottle(int levels) {
        ItemStack item = new ItemStack(Material.EXP_BOTTLE, 1);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(DISPLAY_PREFIX + ChatColor.RESET + ChatColor.GRAY + " — " + ChatColor.GOLD + levels + " niveau" + (levels > 1 ? "x" : ""));
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Contient : " + ChatColor.GOLD + "" + ChatColor.BOLD + levels + " niveau" + (levels > 1 ? "x" : ""),
                ChatColor.GRAY + "Clic droit pour récupérer l'XP.",
                LORE_TAG
        ));

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Retourne le nombre de niveaux stockés dans la bouteille, ou -1 si ce n'est pas une bouteille XP custom.
     */
    public static int getLevels(ItemStack item) {
        if (item == null || item.getType() != Material.EXP_BOTTLE) return -1;
        if (!item.hasItemMeta()) return -1;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return -1;
        if (!meta.hasLore()) return -1;

        List<String> lore = meta.getLore();
        // Vérifier le tag unique dans le lore
        boolean hasTag = lore.stream().anyMatch(line -> line.equals(LORE_TAG));
        if (!hasTag) return -1;

        // Lire la valeur depuis le lore (ligne 0 : "Contient : X niveau(x)")
        for (String line : lore) {
            String stripped = ChatColor.stripColor(line);
            if (stripped.startsWith("Contient : ")) {
                String[] parts = stripped.split(" ");
                // parts[2] = le nombre
                try {
                    return Integer.parseInt(parts[2]);
                } catch (Exception e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Vérifie si un ItemStack est bien une bouteille XP custom.
     */
    public static boolean isBottleXp(ItemStack item) {
        return getLevels(item) >= 0;
    }
}

