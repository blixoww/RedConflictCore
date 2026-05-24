package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /block — Convertit lingots/gemmes de l'inventaire en blocs (9 → 1).
 * Fonctionne sur l'ensemble de l'inventaire, pas seulement la main.
 * Utilise les IDs numériques pour supporter les items custom (steel/ruby/cobalt).
 */
@SuppressWarnings("deprecation")
public class BlockCommand implements CommandExecutor {

    // typeId source → typeId bloc résultat
    private static final Map<Integer, Integer> CONVERSIONS = new LinkedHashMap<>();

    static {
        // Vanilla
        CONVERSIONS.put(265, 42);   // iron_ingot      → iron_block
        CONVERSIONS.put(266, 41);   // gold_ingot       → gold_block
        CONVERSIONS.put(264, 57);   // diamond          → diamond_block
        CONVERSIONS.put(388, 133);  // emerald          → emerald_block
        CONVERSIONS.put(263, 173);  // coal             → coal_block
        CONVERSIONS.put(331, 152);  // redstone         → redstone_block
        // Custom mod
        CONVERSIONS.put(452, 201);  // steel_ingot      → steel_block
        CONVERSIONS.put(453, 202);  // ruby             → ruby_block
        CONVERSIONS.put(454, 203);  // cobalt_ingot     → cobalt_block
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

        for (Map.Entry<Integer, Integer> entry : CONVERSIONS.entrySet()) {
            int ingotId = entry.getKey();
            int blockId = entry.getValue();

            int count = countById(inv, ingotId);
            int sets   = count / 9;
            if (sets == 0) continue;

            removeById(inv, ingotId, sets * 9);

            ItemStack toAdd = new ItemStack(blockId, sets);
            Map<Integer, ItemStack> leftover = inv.addItem(toAdd);
            if (!leftover.isEmpty()) {
                for (ItemStack ls : leftover.values()) {
                    inv.addItem(new ItemStack(ingotId, ls.getAmount() * 9));
                }
            } else {
                totalConverted += sets;
            }
        }

        if (totalConverted == 0) {
            p.sendMessage(RC.BLOCK_NOTHING);
        } else {
            p.sendMessage(RC.fmt(RC.BLOCK_OK, totalConverted));
        }
        return true;
    }

    private int countById(PlayerInventory inv, int typeId) {
        int total = 0;
        for (ItemStack is : inv.getContents()) {
            if (is != null && is.getTypeId() == typeId) total += is.getAmount();
        }
        return total;
    }

    private void removeById(PlayerInventory inv, int typeId, int amount) {
        int remaining = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack is = contents[i];
            if (is == null || is.getTypeId() != typeId) continue;
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
