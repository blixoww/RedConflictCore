package fr.originsfight.giveall;

import org.bukkit.ChatColor;
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
import java.util.Map;

/**
 * Gère les interactions avec l'inventaire /giveall.
 */
public class GiveAllListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        Inventory inv = event.getInventory();
        if (!inv.getTitle().equals(GiveAllCommand.INV_TITLE)) return;

        int slot = event.getRawSlot();

        // Empêcher de modifier les slots UI (ligne du bas : 45-53)
        if (slot >= 45 && slot <= 53) {
            event.setCancelled(true);

            // Bouton Envoyer
            if (slot == GiveAllCommand.SLOT_SEND) {
                distributeItems(player, inv);
            }
            // Bouton Annuler
            else if (slot == GiveAllCommand.SLOT_CANCEL) {
                // Rendre les items au joueur avant de fermer
                returnItemsToPlayer(player, inv);
                player.closeInventory();
            }
        }
    }

    /**
     * Si le joueur ferme l'inventaire sans cliquer Envoyer → on lui rend ses items.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        Inventory inv = event.getInventory();

        if (!inv.getTitle().equals(GiveAllCommand.INV_TITLE)) return;

        // Vérifier s'il reste des items dans la zone items (slots 0-44)
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                returnItemsToPlayer(player, inv);
                player.sendMessage(ChatColor.YELLOW + "Les items non distribués vous ont été rendus.");
                break;
            }
        }
    }

    // ── Distribution ──────────────────────────────────────────────────────────

    private void distributeItems(Player sender, Inventory inv) {
        // Collecter les items (slots 0-44)
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
                inv.setItem(i, null); // Vider l'inventaire immédiatement
            }
        }

        if (items.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Aucun item à distribuer !");
            return;
        }

        // Fermer l'inventaire avant la distribution
        sender.closeInventory();

        int playerCount = 0;
        for (Player target : sender.getServer().getOnlinePlayers()) {
            for (ItemStack item : items) {
                Map<Integer, ItemStack> leftover = target.getInventory().addItem(item.clone());
                for (ItemStack drop : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), drop);
                    target.sendMessage(ChatColor.YELLOW + "⚠ Un item du /giveall n'a pas pu rentrer dans votre inventaire et a été droppé à vos pieds !");
                }
            }
            target.sendMessage(ChatColor.GREEN + "✔ Vous avez reçu des items de la part de "
                    + ChatColor.GOLD + sender.getName() + ChatColor.GREEN + " !");
            playerCount++;
        }

        sender.sendMessage(ChatColor.GREEN + "✔ Items distribués à " + ChatColor.GOLD + playerCount
                + " joueur" + (playerCount > 1 ? "s" : "") + ChatColor.GREEN + " !");
    }

    private void returnItemsToPlayer(Player player, Inventory inv) {
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                inv.setItem(i, null);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }
}

