package fr.originsfight.rtp;

import fr.originsfight.OriginsFightCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RTPCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            OriginsFightCore.getInstance().getLogger().info("Commande RTP exécutée par " + player.getName() + " !");
            RTP.instance().isTeleporting(player);
            return true;
        }
        return false;
    }
}
