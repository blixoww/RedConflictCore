package fr.redconflict.repair;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.cooldown.CooldownManager;
import fr.redconflict.cooldown.CooldownType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/** /repairall — répare inventaire et armure, cooldown 24 h (armé seulement si un item a été réparé). */
public class RepairCommand extends CoreCommand {

    public RepairCommand(JavaPlugin plugin) {
        super(plugin, "repairall", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        long left = CooldownManager.instance().timeLeft(player, CooldownType.REPAIR);
        if (left > 0) {
            player.sendMessage(Text.fmt(RC.REPAIR_COOLDOWN, Text.duration(left)));
            return;
        }

        PlayerInventory inv = player.getInventory();
        boolean repaired = RepairItems.repair(inv.getContents()) | RepairItems.repair(inv.getArmorContents());
        if (!repaired) {
            player.sendMessage(RC.REPAIR_NOTHING);
            return;
        }
        player.sendMessage(RC.REPAIR_DONE);
        CooldownManager.instance().set(player, CooldownType.REPAIR, 24, TimeUnit.HOURS);
    }
}
