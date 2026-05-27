package fr.originsfight.job;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/**
 * Donne de l'XP Artisan : craft, brassage, enchantement, enclume.
 */
public class JobArtisanListener implements Listener {

    private final JobManager manager;
    private final JobConfig  config;

    public JobArtisanListener(JobManager manager, JobConfig config) {
        this.manager = manager;
        this.config  = config;
    }

    // ── Craft ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        int xpPerItem = config.getArtisanXp("craft");
        if (xpPerItem <= 0) return;

        ItemStack result = event.getInventory().getResult();
        if (result == null || config.isCraftBlacklisted(result.getType())) return;
        int qty = result.getAmount();
        // Shift-click craft donne toute la quantité craftable
        if (event.isShiftClick()) {
            qty = estimateShiftCraftQty(event.getInventory());
        }
        manager.giveXp(player, JobType.ARTISAN, xpPerItem * Math.max(1, qty));
    }

    private int estimateShiftCraftQty(CraftingInventory inv) {
        ItemStack result = inv.getResult();
        if (result == null) return 1;
        int stackSize = result.getAmount();
        int min       = 64 / stackSize;
        for (ItemStack mat : inv.getMatrix()) {
            if (mat == null || Material.AIR.equals(mat.getType())) continue;
            min = Math.min(min, mat.getAmount());
        }
        return Math.max(1, min) * stackSize;
    }

    // ── Brassage ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        // BrewEvent ne donne pas directement l'acteur → on attribue l'XP aux joueurs
        // ayant le brassage comme métier actif dans les 5 blocs environnants.
        // Solution simple : on écoute InventoryClickEvent sur le brewing stand.
    }

    /**
     * Approche alternative : détecter quand un joueur prend une potion brassée.
     * On écoute InventoryClickEvent sur un brewing stand pour détecter l'extraction.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getType() != InventoryType.BREWING) return;
        Player player = (Player) event.getWhoClicked();

        // Slots 0-2 = potions (résultat du brassage)
        int slot = event.getRawSlot();
        if (slot < 0 || slot > 2) return;
        ItemStack item = event.getCurrentItem();
        if (item == null) return;

        // Vérifier que c'est bien une potion
        String typeName = item.getType().name();
        if (!typeName.contains("POTION")) return;

        int xp = config.getArtisanXp("brew");
        if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
    }

    // ── Enchantement ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        int xp = config.getArtisanXp("enchant");
        if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
    }

    // ── Enclume ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (event.getRawSlot() != 2) return;
        ItemStack result = event.getCurrentItem();
        if (result == null) return;

        Player player = (Player) event.getWhoClicked();

        int xp = config.getArtisanXp("anvil");
        if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
    }
}





