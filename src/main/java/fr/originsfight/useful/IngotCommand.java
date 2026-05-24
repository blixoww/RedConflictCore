package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

/**
 * /ingot — Convertit pépites de l'inventaire en lingots (9 pépites → 1 lingot).
 */
public class IngotCommand implements CommandExecutor {

    private static final Map<Material, Material> CONVERSIONS = new HashMap<>();

    static {
        CONVERSIONS.put(Material.GOLD_NUGGET, Material.GOLD_INGOT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player p = (Player) sender;
        PlayerInventory inv = p.getInventory();

        int totalConverted = 0;

        for (Map.Entry<Material, Material> entry : CONVERSIONS.entrySet()) {
            Material nugget = entry.getKey();
            Material ingot  = entry.getValue();

            int count = countMaterial(inv, nugget);
            int sets   = count / 9;
            if (sets == 0) continue;

            removeMaterial(inv, nugget, sets * 9);

            ItemStack toAdd = new ItemStack(ingot, sets);
            Map<Integer, ItemStack> leftover = inv.addItem(toAdd);
            if (!leftover.isEmpty()) {
                for (ItemStack ls : leftover.values()) {
                    int ingots = ls.getAmount();
                    inv.addItem(new ItemStack(nugget, ingots * 9));
                }
            } else {
                totalConverted += sets;
            }
        }

        if (totalConverted == 0) {
            p.sendMessage(RC.INGOT_NOTHING);
        } else {
            p.sendMessage(RC.fmt(RC.INGOT_OK, totalConverted));
        }
        return true;
    }

    private int countMaterial(PlayerInventory inv, Material mat) {
        int total = 0;
        for (ItemStack is : inv.getContents()) {
            if (is != null && is.getType() == mat) total += is.getAmount();
        }
        return total;
    }

    private void removeMaterial(PlayerInventory inv, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack is = contents[i];
            if (is == null || is.getType() != mat) continue;
            if (is.getAmount() <= remaining) {
                remaining -= is.getAmount();
                contents[i] = null;
            } else {
                is.setAmount(is.getAmount() - remaining);
                remaining = 0;
            }
        }
        inv.setContents(contents);
    }
}
