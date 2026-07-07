package fr.originsfight.bottlexp;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import fr.originsfight.core.text.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /bottlexp — embouteille tous les niveaux du joueur (minimum 10) dans une fiole. */
public class BottleXpCommand extends CoreCommand {

    private static final int MIN_LEVEL = 10;

    public BottleXpCommand(JavaPlugin plugin) {
        super(plugin, "bottlexp", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (player.getLevel() < MIN_LEVEL) {
            player.sendMessage(Text.fmt(RC.BXP_NOT_ENOUGH, player.getLevel()));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(RC.BXP_INV_FULL);
            return;
        }

        int levels = player.getLevel();
        player.setLevel(0);
        player.setExp(0f);
        player.getInventory().addItem(BottleXpItem.createBottle(levels));
        player.sendMessage(Text.fmt(RC.BXP_SUCCESS, levels));
    }
}
