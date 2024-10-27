package fr.originsfight.repair;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;

public class RepairItems {

    public static boolean canRepair(ItemStack itemStack) {
        return itemStack.getDurability() > 0;
    }

    public static boolean repair(ItemStack[] items) {
        if (items == null) return false;
        return Arrays.stream(items)
                .filter(Objects::nonNull)
                .filter(RepairItems::canRepair)
                .peek(a -> a.setDurability((short) 0))
                .count() > 0;
    }
}
