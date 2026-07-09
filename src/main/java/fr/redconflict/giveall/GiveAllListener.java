package fr.redconflict.giveall;

import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactions de l'inventaire /giveall : bouton Envoyer (distribution à tous
 * les joueurs en ligne), bouton Annuler, et restitution des items non
 * distribués à la fermeture.
 */
public class GiveAllListener implements Listener {

    private static final int ITEM_SLOTS = 45;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory inv = event.getInventory();
        if (!GiveAllCommand.INV_TITLE.equals(inv.getTitle())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < ITEM_SLOTS || slot > 53) {
            return;
        }
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        if (slot == GiveAllCommand.SLOT_SEND) {
            distribute(player, inv);
        } else if (slot == GiveAllCommand.SLOT_CANCEL) {
            returnItems(player, inv);
            player.closeInventory();
            player.sendMessage(RC.GIVEALL_CANCEL);
        }
    }

    /** Fermeture sans envoi : rend les items restants au joueur. */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!GiveAllCommand.INV_TITLE.equals(inv.getTitle()) || !(event.getPlayer() instanceof Player)) {
            return;
        }
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                returnItems((Player) event.getPlayer(), inv);
                event.getPlayer().sendMessage(RC.GIVEALL_RETURNED);
                return;
            }
        }
    }

    private void distribute(Player sender, Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
                inv.setItem(i, null);
            }
        }
        if (items.isEmpty()) {
            sender.sendMessage(RC.GIVEALL_EMPTY);
            return;
        }
        sender.closeInventory();

        int count = 0;
        for (Player target : sender.getServer().getOnlinePlayers()) {
            boolean dropped = false;
            for (ItemStack item : items) {
                for (ItemStack overflow : target.getInventory().addItem(item.clone()).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), overflow);
                    dropped = true;
                }
            }
            if (dropped) {
                target.sendMessage(RC.GIVEALL_DROPPED);
            }
            target.sendMessage(Text.fmt(RC.GIVEALL_RECEIVED, sender.getName()));
            count++;
        }
        sender.sendMessage(Text.fmt(RC.GIVEALL_SENT, count));
    }

    private void returnItems(Player player, Inventory inv) {
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                inv.setItem(i, null);
                for (ItemStack overflow : player.getInventory().addItem(item.clone()).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
            }
        }
    }
}
