package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** /poubelle — inventaire jetable dont le contenu est détruit à la fermeture. */
public class PoubelleCommand extends CoreCommand implements Listener {

    public PoubelleCommand(JavaPlugin plugin) {
        super(plugin, "poubelle", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        ((Player) sender).openInventory(Bukkit.createInventory(null, 54, RC.TRASH_TITLE));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (RC.TRASH_TITLE.equals(event.getInventory().getTitle())) {
            event.getInventory().clear();
        }
    }
}
