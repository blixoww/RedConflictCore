package fr.redconflict.hdv;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Utilitaire pour les livres enchantés.
 *
 * - Traduction des noms d'enchantements en français
 * - Extraction des enchantements d'un livre (EnchantmentStorageMeta)
 * - Génération du nom d'affichage : "Livre Enchanté" + liste des enchantements
 * - Recherche par nom d'enchantement dans le filtre HDV
 * - Pose des enchantements d'un livre (voir {@link #apply}) : sur un livre, ils
 *   vont dans {@code StoredEnchantments}, jamais dans {@code ench}.
 */
public class EnchantUtils {

    private static final Map<String, String> FR_NAMES = new LinkedHashMap<>();

    static {
        // Épée / combat
        FR_NAMES.put("DAMAGE_ALL",          "Tranchant");
        FR_NAMES.put("DAMAGE_UNDEAD",       "Châtiment");
        FR_NAMES.put("DAMAGE_ARTHROPODS",   "Fléau des Arthropodes");
        FR_NAMES.put("KNOCKBACK",           "Recul");
        FR_NAMES.put("FIRE_ASPECT",         "Aspect Igné");
        FR_NAMES.put("LOOT_BONUS_MOBS",     "Pillage");
        // Arc
        FR_NAMES.put("ARROW_DAMAGE",        "Puissance");
        FR_NAMES.put("ARROW_KNOCKBACK",     "Recul");
        FR_NAMES.put("ARROW_FIRE",          "Flamme");
        FR_NAMES.put("ARROW_INFINITE",      "Infini");
        // Outil
        FR_NAMES.put("DIG_SPEED",           "Efficacité");
        FR_NAMES.put("SILK_TOUCH",          "Toucher de Soie");
        FR_NAMES.put("DURABILITY",          "Solidité");
        FR_NAMES.put("LOOT_BONUS_BLOCKS",   "Fortune");
        // Pêche
        FR_NAMES.put("LURE",                "Appât");
        FR_NAMES.put("LUCK",                "Chance de la Mer");
        // Armure
        FR_NAMES.put("PROTECTION_ENVIRONMENTAL", "Protection");
        FR_NAMES.put("PROTECTION_FIRE",          "Protection contre le Feu");
        FR_NAMES.put("PROTECTION_FALL",          "Chute Amortie");
        FR_NAMES.put("PROTECTION_EXPLOSIONS",    "Protection contre les Explosions");
        FR_NAMES.put("PROTECTION_PROJECTILE",    "Protection contre les Projectiles");
        FR_NAMES.put("THORNS",                   "Épines");
        FR_NAMES.put("WATER_WORKER",             "Affinité Aquatique");
        FR_NAMES.put("OXYGEN",                   "Respiration");
        FR_NAMES.put("DEPTH_STRIDER",            "Pas des Profondeurs");
        // Divers
        FR_NAMES.put("MENDING",                  "Réparation");
    }

    /** Retourne le nom français d'un enchantement, ou son nom interne en minuscules si inconnu. */
    public static String frenchName(Enchantment ench) {
        String key = ench.getName().toUpperCase();
        return FR_NAMES.getOrDefault(key, capitalize(ench.getName().toLowerCase().replace("_", " ")));
    }

    /** Retourne le niveau en chiffres romains (I, II, III, IV, V). */
    public static String romanLevel(int level) {
        switch (level) {
            case 1:  return "I";
            case 2:  return "II";
            case 3:  return "III";
            case 4:  return "IV";
            case 5:  return "V";
            default: return String.valueOf(level);
        }
    }

    /**
     * Retourne true si l'item est un livre enchanté (ENCHANTED_BOOK)
     * avec des enchantements stockés.
     */
    public static boolean isEnchantedBook(ItemStack item) {
        if (item == null) return false;
        if (item.getType().name().equals("ENCHANTED_BOOK")) return true;
        return false;
    }

    /**
     * Pose un enchantement sur un item, au bon endroit.
     *
     * <p><b>Un livre ne porte pas ses enchantements comme le reste.</b> Sur
     * n'importe quel item, {@code addUnsafeEnchantment} écrit la balise
     * {@code ench} — l'enchantement agit sur l'objet lui-même. Sur un livre, il
     * doit aller dans {@code StoredEnchantments} : c'est le seul endroit où
     * l'enclume va le chercher pour le transférer à un équipement.
     *
     * <p>Un livre enchanté par {@code ench} <b>ressemble</b> pourtant à un livre
     * normal : l'info-bulle affiche l'enchantement et l'objet brille. Mais
     * l'enclume le refuse (croix rouge, aucun résultat), et un simple renommage
     * l'efface — l'enclume réécrit les enchantements depuis
     * {@code StoredEnchantments}, qui est vide, et supprime {@code ench} au
     * passage.
     */
    public static void apply(ItemStack item, Enchantment enchantment, int level) {
        if (item == null || enchantment == null) return;
        if (isEnchantedBook(item)) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta) {
                ((EnchantmentStorageMeta) meta).addStoredEnchant(enchantment, level, true);
                item.setItemMeta(meta);
                return;
            }
        }
        item.addUnsafeEnchantment(enchantment, level);
    }

    /**
     * Retourne la map des enchantements stockés dans un livre enchanté.
     * Vide si ce n'est pas un livre ou pas d'enchantements.
     */
    public static Map<Enchantment, Integer> getStoredEnchants(ItemStack item) {
        if (!isEnchantedBook(item)) return Collections.emptyMap();
        if (!item.hasItemMeta()) return Collections.emptyMap();
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof EnchantmentStorageMeta)) return Collections.emptyMap();
        return ((EnchantmentStorageMeta) meta).getStoredEnchants();
    }

    /**
     * Génère le nom d'affichage français pour un livre enchanté.
     * Exemple : "§6Livre Enchanté §8| §bTranchant V"
     * Si plusieurs enchantements : "§6Livre Enchanté §8| §bTranchant V, Solidité III"
     */
    public static String getDisplayName(ItemStack item) {
        Map<Enchantment, Integer> enchants = getStoredEnchants(item);
        if (enchants.isEmpty()) return "§6Livre Enchanté";

        List<String> parts = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            parts.add("§b" + frenchName(entry.getKey()) + " " + romanLevel(entry.getValue()));
        }
        return "§6Livre Enchanté §8| " + join(parts, "§8, ");
    }

    /**
     * Génère les lignes de lore pour un livre enchanté (une ligne par enchantement).
     */
    public static List<String> getLore(ItemStack item) {
        Map<Enchantment, Integer> enchants = getStoredEnchants(item);
        List<String> lore = new ArrayList<>();
        if (enchants.isEmpty()) return lore;
        lore.add("§8---------- Enchantements ----------");
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            lore.add("§b  " + frenchName(entry.getKey()) + " §8| §f" + romanLevel(entry.getValue()));
        }
        return lore;
    }

    /**
     * Retourne une chaîne searchable pour le filtre HDV.
     * Contient : "livre enchante", chaque nom fr d'enchantement.
     * Ex: "livre enchante tranchant solidite"
     */
    public static String getSearchString(ItemStack item) {
        if (!isEnchantedBook(item)) return "";
        Map<Enchantment, Integer> enchants = getStoredEnchants(item);
        StringBuilder sb = new StringBuilder("livre enchante enchanted book");
        for (Enchantment ench : enchants.keySet()) {
            sb.append(" ").append(frenchName(ench).toLowerCase());
            sb.append(" ").append(ench.getName().toLowerCase());
        }
        return sb.toString();
    }

    /**
     * Applique les métadonnées françaises sur un item livre enchanté en place.
     * Si l'item n'est pas un livre enchanté, ne fait rien.
     */
    public static void applyFrenchMeta(ItemStack item) {
        if (!isEnchantedBook(item)) return;
        if (!item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        // Ne pas écraser un nom personnalisé existant
        if (!meta.hasDisplayName()) {
            meta.setDisplayName(getDisplayName(item));
        }
        List<String> existingLore = meta.hasLore() ? meta.getLore() : new ArrayList<String>();
        List<String> enchLore = getLore(item);
        if (!enchLore.isEmpty() && !existingLore.containsAll(enchLore)) {
            List<String> merged = new ArrayList<>(enchLore);
            for (String l : existingLore) {
                if (!enchLore.contains(l)) merged.add(l);
            }
            meta.setLore(merged);
        }
        item.setItemMeta(meta);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!word.isEmpty())
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}

