package fr.originsfight.repair;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import fr.originsfight.utils.TimeUnits;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

public class RepairCommand implements CommandExecutor {

    private boolean itemsRepaired = false;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        PlayerInventory inventory = player.getInventory();
        long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.REPAIR);
        if (timeLeft > 0) {
            player.sendMessage("§cTu dois encore attendre " + CooldownManager.getFormattedTimeLeft(timeLeft) + " avant de pouvoir réparer tes items.");
            return true;
        }


        itemsRepaired |= RepairItems.repair(inventory.getContents());
        itemsRepaired |= RepairItems.repair(inventory.getArmorContents());

        if (!itemsRepaired) {
            player.sendMessage("§cTu n'as pas d'items à réparer.");
            return true;
        }

        player.sendMessage("§eTous tes items ont été réparés.");
        CooldownManager.instance().set(player, 24, TimeUnits.HOURS, CooldownType.REPAIR);
        return true;
    }
}