package fr.originsfight.repair;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;

public class RepairItems {

    public static boolean canRepair(ItemStack itemStack) {
        return itemStack.getType().getMaxDurability() > 0 && itemStack.getDurability() > 0;
    }

    public static boolean repair(ItemStack[] items) {
        if (items == null) return false;
        boolean anyItemRepaired = false;

        for (ItemStack item : items) {
            if (item != null && canRepair(item)) {
                item.setDurability((short) 0);
                anyItemRepaired = true;
            }
        }

        return anyItemRepaired;
    }
}
