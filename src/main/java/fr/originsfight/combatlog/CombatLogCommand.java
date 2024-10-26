package fr.originsfight.combatlog;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CombatLogCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.COMBAT);
        if (player.isOp()) {
            player.sendMessage("§cTu es OP, tu n'es pas affecté par le combatlog.");
            return true;
        }
        if (timeLeft > 0) {
            player.sendMessage("§cTu es en combat, tu dois encore attendre " + CooldownManager.getFormattedTimeLeft(timeLeft) + " avant de pouvoir te déconnecter.");
            return true;
        } else {
            player.sendMessage("§eTu n'es pas en combat");
        }
        return false;
    }
}
