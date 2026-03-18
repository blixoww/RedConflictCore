package fr.originsfight.repair;

import fr.originsfight.RC;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import fr.originsfight.utils.TimeUnits;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

public class RepairCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;
        long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.REPAIR);
        if (timeLeft > 0) {
            player.sendMessage(RC.fmt(RC.REPAIR_COOLDOWN, CooldownManager.getFormattedTimeLeft(timeLeft)));
            return true;
        }
        PlayerInventory inv = player.getInventory();
        boolean repaired = RepairItems.repair(inv.getContents()) | RepairItems.repair(inv.getArmorContents());
        if (!repaired) { player.sendMessage(RC.REPAIR_NOTHING); return true; }
        player.sendMessage(RC.REPAIR_DONE);
        CooldownManager.instance().set(player, 24, TimeUnits.HOURS, CooldownType.REPAIR);
        return true;
    }
}