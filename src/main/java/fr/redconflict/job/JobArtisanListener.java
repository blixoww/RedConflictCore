package fr.redconflict.job;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Donne de l'XP Artisan : craft, brassage, enchantement, enclume.
 *
 * <p>Chaque source ne paie que ce qui a réellement été produit ou payé :
 * <ul>
 *   <li><b>craft</b> — rien pour un produit qui se décrafte en ses ingrédients
 *       (lingots ↔ bloc…), voir {@link CraftCycles} ;</li>
 *   <li><b>brassage</b> — seulement la potion qui sort d'un vrai brassage, une
 *       fois. Avant, chaque clic sur une potion de l'alambic payait : la poser
 *       et la reprendre suffisait ;</li>
 *   <li><b>enclume</b> — seulement si des niveaux ont réellement été dépensés.
 *       Avant, cliquer sur le résultat sans pouvoir payer payait quand même.</li>
 * </ul>
 */
public class JobArtisanListener implements Listener {

    private final Plugin     plugin;
    private final JobManager manager;
    private final JobConfig  config;
    private final CraftCycles cycles;

    /** Alambic → emplacements (0-2) dont la potion sort d'un brassage pas encore payé. */
    private final Map<String, boolean[]> freshPotions = new HashMap<String, boolean[]>();

    public JobArtisanListener(Plugin plugin, JobManager manager, JobConfig config, CraftCycles cycles) {
        this.plugin  = plugin;
        this.manager = manager;
        this.config  = config;
        this.cycles  = cycles;
    }

    // ── Craft ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        int xpPerItem = config.getArtisanXp("craft", manager.getLevel(player, JobType.ARTISAN));
        if (xpPerItem <= 0) return;

        ItemStack result = event.getInventory().getResult();
        if (result == null || config.isCraftBlacklisted(result.getType())) return;
        if (cycles.contains(result.getType())) return;
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

    /** Un brassage vient d'aboutir : ses potions sont « fraîches » jusqu'à leur retrait. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        boolean[] fresh = new boolean[3];
        BrewerInventory inv = event.getContents();
        for (int i = 0; i < 3; i++) {
            ItemStack s = inv.getItem(i);
            fresh[i] = s != null && s.getType() == Material.POTION;
        }
        freshPotions.put(key(event.getBlock()), fresh);
    }

    /**
     * Retrait d'une potion de l'alambic : payé une seule fois par potion
     * réellement brassée. Toute autre manipulation de l'emplacement (poser une
     * fiole, reprendre une potion déjà payée) ne rapporte rien.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getType() != InventoryType.BREWING) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot > 2) return;

        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof org.bukkit.block.BrewingStand)) return;
        boolean[] fresh = freshPotions.get(key(((org.bukkit.block.BrewingStand) holder).getBlock()));
        if (fresh == null || !fresh[slot]) return;
        fresh[slot] = false;              // quoi qu'il arrive, l'emplacement a été touché

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() != Material.POTION) return;

        Player player = (Player) event.getWhoClicked();
        int xp = config.getArtisanXp("brew", manager.getLevel(player, JobType.ARTISAN));
        if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
    }

    private static String key(Block b) {
        Location l = b.getLocation();
        return l.getWorld().getName() + ':' + l.getBlockX() + ':' + l.getBlockY() + ':' + l.getBlockZ();
    }

    // ── Enchantement ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        int xp = config.getArtisanXp("enchant", manager.getLevel(player, JobType.ARTISAN));
        if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
    }

    // ── Enclume ───────────────────────────────────────────────────────────────

    /**
     * Le clic sur le résultat arrive AVANT que le serveur vérifie les niveaux :
     * sans les moyens, le clic est refusé mais l'événement part quand même. On
     * compare donc le niveau un tick plus tard — payé seulement s'il a baissé.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (event.getRawSlot() != 2) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) return;

        final Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        final int levelBefore = player.getLevel();

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (!player.isOnline() || player.getLevel() >= levelBefore) return;
                int xp = config.getArtisanXp("anvil", manager.getLevel(player, JobType.ARTISAN));
                if (xp > 0) manager.giveXp(player, JobType.ARTISAN, xp);
            }
        });
    }
}
