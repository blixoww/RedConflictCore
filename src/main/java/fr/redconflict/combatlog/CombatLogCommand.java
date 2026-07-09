package fr.redconflict.combatlog;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.cooldown.CooldownManager;
import fr.redconflict.cooldown.CooldownType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /ct — affiche le temps de combat restant du joueur. */
public class CombatLogCommand extends CoreCommand {

    public CombatLogCommand(JavaPlugin plugin) {
        super(plugin, "ct", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (player.isOp()) {
            player.sendMessage(RC.CT_OP);
            return;
        }
        long left = CooldownManager.instance().timeLeft(player, CooldownType.COMBAT);
        player.sendMessage(left > 0 ? Text.fmt(RC.CT_IN_COMBAT, Text.duration(left)) : RC.CT_NOT_COMBAT);
    }
}
