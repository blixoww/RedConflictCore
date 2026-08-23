package fr.redconflict.annonyme;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /annonyme — bascule le masquage du pseudo, de la faction et du grade. */
public class AnonymeCommand extends CoreCommand {

    private final AnonymeManager anonymeManager;

    public AnonymeCommand(JavaPlugin plugin, AnonymeManager anonymeManager) {
        super(plugin, "annonyme", true);
        this.anonymeManager = anonymeManager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (anonymeManager.toggleAnonymity(player)) {
            player.sendMessage(RC.PRE + "§aVous êtes maintenant annonyme. Votre pseudo et votre faction sont cachés.");
        } else {
            player.sendMessage(RC.PRE + "§cVous n'êtes plus anonyme. Votre pseudo et votre faction sont visibles.");
        }
    }
}
