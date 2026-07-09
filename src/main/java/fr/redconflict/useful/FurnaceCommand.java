package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * /furnace this|all — cuit l'item en main ou tout l'inventaire, d'après
 * l'index des recettes de cuisson vanilla construit par {@link UtilityModule}.
 * Cas particulier : le minerai de lapis donne du lapis (INK_SACK:4), sa
 * « cuisson » vanilla n'ayant pas de sens en 1.8.
 */
public class FurnaceCommand extends CoreCommand {

    private final Map<Material, ItemStack> smeltable;

    public FurnaceCommand(JavaPlugin plugin, Map<Material, ItemStack> smeltable) {
        super(plugin, "furnace", true);
        this.smeltable = smeltable;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("redconflict.furnace")) {
            player.sendMessage(RC.ERR_NO_PERM);
            return;
        }

        if (args.length == 0) {
            player.sendMessage(RC.FURNACE_HELP_HEADER);
            player.sendMessage(RC.FURNACE_HELP_THIS);
            player.sendMessage(RC.FURNACE_HELP_ALL);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "this":
                smeltHand(player);
                break;
            case "all":
                smeltAll(player);
                break;
            default:
                player.sendMessage(RC.FURNACE_HELP_HEADER);
        }
    }

    private void smeltHand(Player player) {
        ItemStack inHand = player.getInventory().getItemInHand();
        if (inHand == null || !smeltable.containsKey(inHand.getType())) {
            player.sendMessage(RC.FURNACE_THIS_FAIL);
            return;
        }
        player.getInventory().setItemInHand(smelt(inHand));
        player.sendMessage(RC.FURNACE_THIS_OK);
    }

    private void smeltAll(Player player) {
        boolean transformed = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !smeltable.containsKey(item.getType())) {
                continue;
            }
            player.getInventory().remove(item);
            player.getInventory().addItem(smelt(item));
            transformed = true;
        }
        player.sendMessage(transformed ? RC.FURNACE_ALL_OK : RC.FURNACE_ALL_FAIL);
    }

    private ItemStack smelt(ItemStack raw) {
        if (raw.getType() == Material.LAPIS_ORE) {
            return new ItemStack(Material.INK_SACK, raw.getAmount(), (short) 4);
        }
        return new ItemStack(smeltable.get(raw.getType()).getType(), raw.getAmount());
    }
}
