package fr.originsfight.useful;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /cobble — filtre la cobblestone au minage : la cobble droppée par les blocs
 * cassés est supprimée au tick suivant tant que le filtre du joueur est actif.
 */
public class CobbleCommand extends CoreCommand implements Listener {

    private final Set<UUID> filtering = new HashSet<>();

    public CobbleCommand(JavaPlugin plugin) {
        super(plugin, "cobble", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (filtering.remove(player.getUniqueId())) {
            player.sendMessage(RC.COBBLE_OFF);
        } else {
            filtering.add(player.getUniqueId());
            player.sendMessage(RC.COBBLE_ON);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!filtering.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type != Material.COBBLESTONE && type != Material.STONE) {
            return;
        }
        // Le drop n'existe qu'au tick suivant le break.
        Bukkit.getScheduler().runTask(plugin, () ->
                event.getBlock().getLocation().getWorld()
                        .getNearbyEntities(event.getBlock().getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5)
                        .stream()
                        .filter(e -> e instanceof Item)
                        .map(e -> (Item) e)
                        .filter(item -> item.getItemStack().getType() == Material.COBBLESTONE)
                        .forEach(Item::remove));
    }
}
