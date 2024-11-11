package fr.originsfight.useful;

import fr.originsfight.OriginsFightCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class FurnaceCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Cette commande ne peut être exécutée que par un joueur.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("§6Utilisation de la commande §e/furnace§6 :\n\n" +
                    " §8» §e/furnace this §7: §fPermet de cuire uniquement l'item que vous tenez dans votre main.\n" +
                    " §8» §e/furnace all §7: §fPermet de cuire tous les items cuisables de votre inventaire.\n");
            return true;
        }

        String option = args[0].toLowerCase();
        boolean transformed = false;

        if (option.equals("this")) {
            ItemStack itemInHand = player.getInventory().getItemInHand();
            if (itemInHand != null && OriginsFightCore.getInstance().getSmeltableItems().containsKey(itemInHand.getType())) {
                ItemStack cookedItem = OriginsFightCore.getInstance().getSmeltableItems().get(itemInHand.getType());
                int itemAmount = itemInHand.getAmount();
                player.getInventory().setItemInHand(new ItemStack(cookedItem.getType(), itemAmount));
                player.sendMessage("§aL'item dans votre main a été cuit !");
                transformed = true;
            } else {
                player.sendMessage("§cL'item dans votre main ne peut pas être cuit.");
            }
        }

        else if (option.equals("all")) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && OriginsFightCore.getInstance().getSmeltableItems().containsKey(item.getType())) {
                    ItemStack cookedItem = OriginsFightCore.getInstance().getSmeltableItems().get(item.getType());
                    int itemAmount = item.getAmount();
                    player.getInventory().remove(item);
                    player.getInventory().addItem(new ItemStack(cookedItem.getType(), itemAmount));
                    transformed = true;
                }
            }
            if (transformed) {
                player.sendMessage("§aTous les items cuisables de votre inventaire ont été transformés !");
            } else {
                player.sendMessage("§cAucun item cuisable trouvé dans votre inventaire.");
            }
        } else {
            player.sendMessage("§cOption invalide. Utilisez /furnace main ou /furnace all.");
        }

        return true;
    }
}
