package fr.originsfight.repair;

import org.bukkit.inventory.ItemStack;

/** Remise à neuf de la durabilité des items réparables. */
public final class RepairItems {

    private RepairItems() {
    }

    /** Répare tous les items endommagés du tableau. @return true si au moins un l'a été. */
    public static boolean repair(ItemStack[] items) {
        if (items == null) {
            return false;
        }
        boolean any = false;
        for (ItemStack item : items) {
            if (item != null && item.getType().getMaxDurability() > 0 && item.getDurability() > 0) {
                item.setDurability((short) 0);
                any = true;
            }
        }
        return any;
    }
}
