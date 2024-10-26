package fr.originsfight.repair;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

public class RepairItems {

    public static boolean canRepair(ItemStack itemStack) {
        return itemStack.getDurability() > 0;
    }

    public static boolean repair(ItemStack[] itemStack) {
        if (itemStack == null) return false;
        return Arrays.stream(itemStack).filter(RepairItems::canRepair).peek(item -> item.setDurability((short) 0)).count() > 0;
    }
}
