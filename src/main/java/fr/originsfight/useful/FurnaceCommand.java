package fr.originsfight.useful;

import fr.originsfight.RC;
import fr.originsfight.OriginsFightCore;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class FurnaceCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(RC.FURNACE_HELP_HEADER);
            player.sendMessage(RC.FURNACE_HELP_THIS);
            player.sendMessage(RC.FURNACE_HELP_ALL);
            return true;
        }

        String option = args[0].toLowerCase();
        boolean transformed = false;

        if (option.equals("this")) {
            ItemStack itemInHand = player.getInventory().getItemInHand();
            if (itemInHand != null && OriginsFightCore.getInstance().getSmeltableItems().containsKey(itemInHand.getType())) {
                int amount = itemInHand.getAmount();
                if (itemInHand.getType() == Material.LAPIS_ORE) {
                    player.getInventory().setItemInHand(new ItemStack(Material.INK_SACK, amount, (short) 4));
                } else {
                    ItemStack cooked = OriginsFightCore.getInstance().getSmeltableItems().get(itemInHand.getType());
                    player.getInventory().setItemInHand(new ItemStack(cooked.getType(), amount));
                }
                player.sendMessage(RC.FURNACE_THIS_OK);
            } else {
                player.sendMessage(RC.FURNACE_THIS_FAIL);
            }
        } else if (option.equals("all")) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null || !OriginsFightCore.getInstance().getSmeltableItems().containsKey(item.getType())) continue;
                int amount = item.getAmount();
                if (item.getType() == Material.LAPIS_ORE) {
                    player.getInventory().remove(item);
                    player.getInventory().addItem(new ItemStack(Material.INK_SACK, amount, (short) 4));
                } else {
                    ItemStack cooked = OriginsFightCore.getInstance().getSmeltableItems().get(item.getType());
                    player.getInventory().remove(item);
                    player.getInventory().addItem(new ItemStack(cooked.getType(), amount));
                }
                transformed = true;
            }
            player.sendMessage(transformed ? RC.FURNACE_ALL_OK : RC.FURNACE_ALL_FAIL);
        }
        return true;
    }
}
