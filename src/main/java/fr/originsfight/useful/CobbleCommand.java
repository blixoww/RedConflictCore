package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /cobble — Toggle le filtrage de la cobblestone au minage.
 * Accessible à tous les joueurs.
 * Quand actif, la cobblestone obtenue lors du minage est automatiquement supprimée.
 */
public class CobbleCommand implements CommandExecutor, Listener {

    private static final Set<UUID> NO_COBBLE = new HashSet<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player p = (Player) sender;
        if (NO_COBBLE.contains(p.getUniqueId())) {
            NO_COBBLE.remove(p.getUniqueId());
            p.sendMessage(RC.COBBLE_OFF);
        } else {
            NO_COBBLE.add(p.getUniqueId());
            p.sendMessage(RC.COBBLE_ON);
        }
        return true;
    }

    /**
     * Quand un joueur ayant le filtre actif mine un bloc,
     * on vérifie si c'est de la cobble (COBBLESTONE ou COBBLESTONE_STAIRS etc.)
     * et on annule l'ajout dans l'inventaire en retirant la cobble juste après le break.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!NO_COBBLE.contains(p.getUniqueId())) return;

        Material type = event.getBlock().getType();
        if (type == Material.COBBLESTONE || type == Material.STONE) {
            // La cobble est droppée comme item sur le sol — on la supprime au tick suivant
            org.bukkit.Bukkit.getScheduler().runTask(
                fr.originsfight.OriginsFightCore.getInstance(),
                () -> event.getBlock().getLocation().getWorld().getNearbyEntities(
                        event.getBlock().getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5
                ).stream()
                    .filter(e -> e instanceof org.bukkit.entity.Item)
                    .map(e -> (org.bukkit.entity.Item) e)
                    .filter(item -> item.getItemStack().getType() == Material.COBBLESTONE)
                    .forEach(org.bukkit.entity.Item::remove)
            );
        }
    }
}

