package fr.originsfight.combatlog;

import fr.originsfight.RC;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CombatLogCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;
        if (player.isOp()) { player.sendMessage(RC.CT_OP); return true; }
        long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.COMBAT);
        if (timeLeft > 0) {
            player.sendMessage(RC.fmt(RC.CT_IN_COMBAT, CooldownManager.getFormattedTimeLeft(timeLeft)));
        } else {
            player.sendMessage(RC.CT_NOT_COMBAT);
        }
        return true;
    }
}
