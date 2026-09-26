package fr.redconflict.succes;

import org.bukkit.Material;
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
import java.util.Map;
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

    /** Série de kills en cours, par joueur. Repart de zéro à la mort. */
    private final Map<UUID, Integer> streaks = new HashMap<>();

    private final SuccesManager manager;
    /**
     * Blocs posés à la main, qui ne comptent pas pour les succès « miner N
     * blocs » : sans ce suivi, une obsidienne ou un minerai (Toucher de soie)
     * posé puis recassé en boucle validait « obsidienne-64 » ou « diamant-256 ».
     * Persisté comme celui du métier Mineur, pour survivre au redémarrage.
     */
    private final fr.redconflict.job.PlacedOreTracker placed;

    public SuccesGameplayListener(SuccesManager manager, fr.redconflict.job.PlacedOreTracker placed) {
        this.manager = manager;
        this.placed = placed;
    }

    // ── Blocs ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // La marque tombe quel que soit le casseur : le bloc quitte l'emplacement.
        if (placed.consume(event.getBlock())) return;
        Player player = event.getPlayer();
        if (ignored(player)) return;

        manager.progress(player, SuccesTrigger.BLOCK_MINE, event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (ignored(player)) return;

        manager.progress(player, SuccesTrigger.BLOCK_PLACE, event.getBlock().getType().name(), 1);
    }

    /**
     * Blocs qui comptent pour les succès de minage même posés à la main.
     *
     * <p>L'obsidienne ne se trouve pas telle quelle dans la nature : elle se
     * fabrique (lave + eau) puis se pose. Refuser les blocs posés rendait donc
     * « obsidienne-64 » quasi impossible. Et la boucle poser/casser reste lente
     * — près de 10 s par bloc à la pioche en diamant —, ce qui la rend peu
     * rentable. C'est la seule exception.
     */
    static final java.util.Set<String> REPLACEABLE_COUNTS =
            java.util.Collections.singleton("OBSIDIAN");

    /** Marque les blocs posés qu'un succès de minage attend (hors du filtre « chargé »). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMarkPlaced(BlockPlaceEvent event) {
        String type = event.getBlock().getType().name();
        if (REPLACEABLE_COUNTS.contains(type)) return;
        if (!manager.getCatalog().matching(SuccesTrigger.BLOCK_MINE, type).isEmpty()) {
            placed.mark(event.getBlock());
        }
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
        // Même IP, ou même victime tuée il y a moins de 10 min : ni kill ni
        // série — sinon deux comptes suffisaient à farmer « 1000 kills ».
        if (!fr.redconflict.core.KillFarmGuard.counts(event)) return;

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
     * Seul cas écarté : un joueur dont l'avancement n'est pas chargé, pour qui
     * il n'y a rien à incrémenter.
     *
     * <p><b>Le créatif ne l'est plus.</b> Il ne protégeait pas grand-chose —
     * la bourse distribue déjà des objets contre de l'argent — et il n'est de
     * toute façon accordé qu'à l'administrateur, à qui il faisait surtout
     * croire que la détection était en panne.
     */
    private boolean ignored(Player player) {
        return player == null || !manager.isLoaded(player.getUniqueId());
    }

}
