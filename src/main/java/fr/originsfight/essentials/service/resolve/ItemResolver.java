package fr.originsfight.essentials.service.resolve;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Résolution des items pour /give : accepte {@code <nom>[:<data>]} ou
 * {@code <id>[:<data>]} (ex. {@code diamond_sword}, {@code 276}, {@code 35:14}).
 */
public class ItemResolver {

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
}
