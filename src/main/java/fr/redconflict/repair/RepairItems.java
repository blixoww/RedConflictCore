package fr.redconflict.repair;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remise à neuf de la durabilité des items réparables.
 *
 * <p><b>Pourquoi ce fichier ne demande plus à {@code Material}.</b> Le test
 * d'origine était {@code item.getType().getMaxDurability() > 0}. Il repose sur
 * une table écrite à la main dans l'API — et cette table est fausse pour les
 * items du serveur : les vingt pièces d'armure custom (acier, émeraude, rubis,
 * cobalt) y sont déclarées avec une durabilité de 0, ce qui les rendait tout
 * simplement invisibles à la réparation. Les outils, eux, y portaient des
 * valeurs sans rapport avec celles du serveur (250 pour une épée en acier qui en
 * vaut 2200).
 *
 * <p>On interroge donc l'objet lui-même : {@code Item.usesDurability()} est la
 * méthode dont le serveur se sert pour décider si un objet s'use, et elle répond
 * juste pour tout — vanilla comme custom, aujourd'hui comme après le prochain
 * ajout d'item.
 *
 * <p><b>Et pourquoi ne pas simplement remettre toute durabilité à zéro.</b>
 * Parce que le champ « durabilité » d'un objet qui ne s'use pas ne veut pas dire
 * durabilité : c'est sa variante. La laine rouge, les colorants, les bûches et
 * les potions se distinguent par ce nombre. Le remettre à zéro transformerait la
 * laine rouge en laine blanche — {@code usesDurability()} exclut précisément ces
 * objets-là, puisqu'il exige l'absence de sous-types.
 */
public final class RepairItems {

    /** Réponse de {@code usesDurability()} par type, pour ne pas refaire la réflexion. */
    private static final Map<Material, Boolean> DAMAGEABLE = new ConcurrentHashMap<Material, Boolean>();

    private RepairItems() {
    }

    /**
     * Répare tous les items endommagés du tableau.
     *
     * @return {@code true} si au moins un l'a été
     */
    public static boolean repair(ItemStack[] items) {
        if (items == null) {
            return false;
        }
        boolean any = false;
        for (ItemStack item : items) {
            if (item == null || item.getType() == null || item.getDurability() <= 0) {
                continue;
            }
            if (!isDamageable(item)) {
                continue;
            }
            item.setDurability((short) 0);
            any = true;
        }
        return any;
    }

    /**
     * L'objet s'use-t-il, ou son « damage » est-il une variante ?
     *
     * <p>Réponse donnée par le serveur lui-même, mise en cache par type : la
     * réflexion n'a lieu qu'une fois par matériau et par démarrage.
     */
    private static boolean isDamageable(ItemStack item) {
        Material type = item.getType();
        Boolean known = DAMAGEABLE.get(type);
        if (known != null) {
            return known.booleanValue();
        }
        boolean damageable = askServer(item);
        DAMAGEABLE.put(type, Boolean.valueOf(damageable));
        return damageable;
    }

    /**
     * {@code CraftItemStack.asNMSCopy(item).getItem().usesDurability()}.
     *
     * <p>En cas d'échec — version de serveur inattendue, objet sans équivalent
     * NMS — on retombe sur la table de l'API. Elle est incomplète, mais un repli
     * qui répare trop peu vaut mieux qu'un repli qui abîme une pile de laine.
     */
    private static boolean askServer(ItemStack item) {
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftStack = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Class<?> nmsStack = Class.forName("net.minecraft.server." + version + ".ItemStack");
            Object copy = craftStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            if (copy == null) {
                return item.getType().getMaxDurability() > 0;
            }
            Object nmsItem = nmsStack.getMethod("getItem").invoke(copy);
            if (nmsItem == null) {
                return item.getType().getMaxDurability() > 0;
            }
            Object uses = nmsItem.getClass().getMethod("usesDurability").invoke(nmsItem);
            return Boolean.TRUE.equals(uses);
        } catch (Throwable ignored) {
            return item.getType().getMaxDurability() > 0;
        }
    }
}
