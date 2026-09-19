package fr.redconflict.succes;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Branche les faits de jeu sur les compteurs de succès.
 *
 * <p>Ce fichier ne connaît <b>aucun succès</b> : il annonce ce qui vient de se
 * passer ({@code BLOCK_MINE:OBSIDIAN}, {@code KILL_PLAYER}...) et le catalogue
 * décide qui avance. C'est ce qui permet d'ajouter « miner 512 obsidiennes »
 * dans le YAML sans revenir ici.
 */
public class SuccesGameplayListener implements Listener {

    /**
     * Blocs posés par un joueur depuis le démarrage : ils ne comptent pas
     * quand on les recasse.
     *
     * <p>Sans ce garde-fou, « miner 64 obsidiennes » se gagne en posant et
     * recassant la même pile — l'obsidienne se récupère intégralement. Le jeu
     * de positions est <b>borné</b> : au-delà de {@link #PLACED_CAP} entrées, la
     * plus ancienne est oubliée. Une mémoire longue n'a pas d'intérêt ici (on
     * vise le va-et-vient immédiat) et un ensemble sans limite finirait par
     * peser sur un serveur qui tourne des semaines.
     */
    private static final int PLACED_CAP = 20000;
    private final Set<String> placed = new LinkedHashSet<>();

    /** Série de kills en cours, par joueur. Repart de zéro à la mort. */
    private final Map<UUID, Integer> streaks = new HashMap<>();

    private final SuccesManager manager;

    public SuccesGameplayListener(SuccesManager manager) {
        this.manager = manager;
    }

    // ── Blocs ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (ignored(player)) return;

        String key = keyOf(event.getBlock());
        if (placed.remove(key)) return;   // posé par un joueur : ne compte pas

        manager.progress(player, SuccesTrigger.BLOCK_MINE, event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (ignored(player)) return;

        if (placed.size() >= PLACED_CAP) {
            placed.remove(placed.iterator().next());
        }
        placed.add(keyOf(event.getBlock()));

        manager.progress(player, SuccesTrigger.BLOCK_PLACE, event.getBlock().getType().name(), 1);
    }

    // ── Établi, four, table d'enchantement ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (ignored(player)) return;

        ItemStack result = event.getInventory().getResult();
        if (result == null) return;

        int quantity = event.isShiftClick()
                ? shiftCraftQuantity(event.getInventory())
                : result.getAmount();
        manager.progress(player, SuccesTrigger.CRAFT, result.getType().name(), Math.max(1, quantity));
    }

    /**
     * Quantité réellement produite par un craft avec Maj enfoncée.
     *
     * <p>Même calcul que {@code JobArtisanListener} : la grille limite le
     * nombre de répétitions à l'ingrédient le moins fourni.
     */
    private int shiftCraftQuantity(CraftingInventory inventory) {
        ItemStack result = inventory.getResult();
        if (result == null) return 1;
        int perCraft = Math.max(1, result.getAmount());
        int repeats = 64 / perCraft;
        for (ItemStack ingredient : inventory.getMatrix()) {
            if (ingredient == null || ingredient.getType() == Material.AIR) continue;
            repeats = Math.min(repeats, ingredient.getAmount());
        }
        return Math.max(1, repeats) * perCraft;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (ignored(player)) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        manager.progress(player, SuccesTrigger.CONSUME, item.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (ignored(player)) return;
        manager.progress(player, SuccesTrigger.ENCHANT, 1);
    }

    // ── Combat ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        streaks.remove(victim.getUniqueId());
        if (!ignored(victim)) {
            manager.progress(victim, SuccesTrigger.DEATH, 1);
        }

        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || ignored(killer)) return;

        manager.progress(killer, SuccesTrigger.KILL_PLAYER, 1);

        Integer current = streaks.get(killer.getUniqueId());
        int streak = (current == null ? 0 : current) + 1;
        streaks.put(killer.getUniqueId(), streak);
        // Compteur de record : on annonce la valeur atteinte, pas un incrément.
        manager.progress(killer, SuccesTrigger.KILLSTREAK, "", streak);
    }

    /** Oublie la série d'un joueur qui se déconnecte. */
    public void forget(UUID uuid) {
        streaks.remove(uuid);
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    /**
     * Le mode créatif ne compte pour rien : les blocs y sont gratuits et les
     * morts sans conséquence.
     */
    private boolean ignored(Player player) {
        return player == null
                || player.getGameMode() == GameMode.CREATIVE
                || !manager.isLoaded(player.getUniqueId());
    }

    private String keyOf(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }
}
