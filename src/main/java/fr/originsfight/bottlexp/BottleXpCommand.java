package fr.originsfight.bottlexp;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class BottleXpCommand implements CommandExecutor {

    private static final int MIN_LEVEL = 10;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;
        if (player.getLevel() < MIN_LEVEL) {
            player.sendMessage(RC.fmt(RC.BXP_NOT_ENOUGH, player.getLevel()));
            return true;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(RC.BXP_INV_FULL);
            return true;
        }
        int levels = player.getLevel();
        player.setLevel(0);
        player.setExp(0f);
        ItemStack bottle = BottleXpItem.createBottle(levels);
        player.getInventory().addItem(bottle);
        player.sendMessage(RC.fmt(RC.BXP_SUCCESS, levels));
        return true;
    }
}
