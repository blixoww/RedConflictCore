package fr.originsfight.annonyme;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class AnonymeCommand implements CommandExecutor, TabCompleter {

    private final AnonymeManager anonymeManager;

    public AnonymeCommand(AnonymeManager anonymeManager) {
        this.anonymeManager = anonymeManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Cette commande ne peut être utilisée que par un joueur.");
            return true;
        }

        Player player = (Player) sender;

        if (anonymeManager.toggleAnonymity(player)) {
            player.sendMessage("§aVous êtes maintenant anonyme. Votre pseudo et votre faction sont cachés.");
        } else {
            player.sendMessage("§cVous n'êtes plus anonyme. Votre pseudo et votre faction sont visibles.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
