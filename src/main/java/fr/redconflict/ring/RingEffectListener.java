package fr.redconflict.ring;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Applique les effets des anneaux équipés (côté serveur).
 *
 * Événements : XP (+25%), casse en Fortune IV, dégâts de chute (annulés).
 * Tâche périodique : Saturation (réappliquée) et Aimant (attire les items).
 */
public class RingEffectListener implements Listener {

    private static final double MAGNET_RADIUS  = 5.0D;
    private static final double XP_MULTIPLIER   = 1.25D;

    /** Niveau de Fortune que l'anneau confère à n'importe quel outil. */
    private static final int RING_FORTUNE_LEVEL = 4;

    /**
     * Blocs dont le butin dépend de Fortune, et seulement eux. Une liste
     * blanche plutôt que « tout bloc » : les drops vanilla d'une case sont
     * supprimés puis rejoués, ce qui serait faux pour un bloc à inventaire
     * (coffre, four : son contenu tomberait sur la même case et serait perdu)
     * ou à récolte spéciale (glace, feuilles, herbe à la cisaille).
     */
    private static final Set<Material> FORTUNE_BLOCKS = EnumSet.of(
            Material.COAL_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE,
            Material.LAPIS_ORE, Material.QUARTZ_ORE,
            Material.REDSTONE_ORE, Material.GLOWING_REDSTONE_ORE,
            Material.GLOWSTONE, Material.MELON_BLOCK, Material.GRAVEL,
            Material.CROPS, Material.CARROT, Material.POTATO,
            Material.NETHER_WARTS, Material.COCOA);

    /**
     * Minerais du fork (IDs hors plage vanilla, absents du Material de l'API
     * contre laquelle le Core compile) : ruby, cobalt, minerai aléatoire.
     */
    private static final int RUBY_ORE_ID   = 198;
    private static final int COBALT_ORE_ID = 199;
    private static final int RANDOM_ORE_ID = 205;

    private final JavaPlugin plugin;

    /**
     * Cases dont les drops vanilla sont en cours de remplacement, avec le
     * nombre d'objets supprimés. Vit le temps d'un tick : armé au
     * {@code BlockBreakEvent}, relu et vidé au tick suivant.
     */
    private final Map<Location, int[]> replacing = new HashMap<Location, int[]>();

    public RingEffectListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Démarre la tâche périodique (saturation + aimant). À appeler dans onEnable. */
    public static void startTask(JavaPlugin plugin) {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (RingEffects.hasRing(player, RingEffects.NECKLACE_OF_SATURATION)) {
                        // Saturation niveau 1, réappliquée (durée > période pour rester continue).
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.SATURATION, 20, 0, true, false), true);
                    }
                    if (RingEffects.hasRing(player, RingEffects.RING_OF_HASTE)) {
                        // Haste II (amplifier=1), ambiant, sans particules visibles — pas de popup.
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.FAST_DIGGING, 40, 1, true, false), true);
                    }
                    if (RingEffects.hasRing(player, RingEffects.MAGNET)) {
                        pullItems(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private static void pullItems(Player player) {
        for (Entity e : player.getNearbyEntities(MAGNET_RADIUS, MAGNET_RADIUS, MAGNET_RADIUS)) {
            if (!(e instanceof Item)) continue;
            Item itemEntity = (Item) e;
            if (itemEntity.isDead()) continue;
            if (itemEntity.getPickupDelay() > 0) continue; // item pas encore ramassable

            org.bukkit.inventory.ItemStack stack = itemEntity.getItemStack();
            if (stack == null || stack.getType() == Material.AIR) continue;

            // Tenter de mettre directement dans l'inventaire du joueur.
            java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover =
                    player.getInventory().addItem(stack);

            if (leftover.isEmpty()) {
                // Tout a été ajouté → supprimer l'entité item.
                itemEntity.remove();
                // Jouer le son de ramassage vanilla et envoyer l'animation.
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.ORB_PICKUP, 0.2f,
                        ((float) Math.random() * 0.1f) + 0.9f);
            } else {
                // Inventaire plein en partie : mettre à jour la stack restante.
                itemEntity.setItemStack(leftover.values().iterator().next());
            }
        }
    }

    @EventHandler
    public void onExpChange(PlayerExpChangeEvent event) {
        if (RingEffects.hasRing(event.getPlayer(), RingEffects.RING_OF_EXPERIENCE)) {
            event.setAmount((int) Math.round(event.getAmount() * XP_MULTIPLIER));
        }
    }

    /**
     * Anneau de Fortune : la casse se comporte comme avec un outil Fortune IV.
     *
     * <p><b>Le calcul n'est pas refait ici, il est délégué au serveur.</b> Les
     * drops vanilla de la case sont supprimés à l'apparition, puis le bloc
     * rejoue sa propre méthode de drop avec Fortune 4 — exactement le chemin
     * qu'emprunte une pioche enchantée. Les règles de chaque bloc restent donc
     * les siennes : multiplicateur des minerais, plafond de la glowstone et du
     * melon, graines des cultures, et les minerais du fork (ruby, cobalt,
     * aléatoire) avec leur propre {@code getDropCount}.
     *
     * <p>Pas de cumul : un outil déjà Fortune IV ou plus garde son niveau, et
     * Silk Touch l'emporte (le joueur a choisi de récupérer le bloc).
     *
     * <p><b>{@code ignoreCancelled = true} n'est pas décoratif ici, c'est la
     * correction d'une duplication d'objets.</b> Sans lui, ce code s'exécutait
     * aussi quand la casse avait été REFUSÉE — zone WorldGuard protégée, claim
     * RedFaction, spawn. La priorité MONITOR va avec : on lit un verdict déjà
     * rendu par les plugins de protection, on ne le modifie pas. Et si un
     * plugin annulait malgré tout après nous, aucun drop vanilla n'apparaît,
     * donc rien n'est rejoué (voir le compteur de {@link #onItemSpawn}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (!RingEffects.hasRing(player, RingEffects.RING_OF_FORTUNE)) return;

        final Block block = event.getBlock();
        if (!isFortuneBlock(block)) return;

        ItemStack tool = player.getItemInHand();
        if (tool != null && tool.getType() != Material.AIR
                && (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0
                 || tool.getEnchantmentLevel(Enchantment.LOOT_BONUS_BLOCKS) >= RING_FORTUNE_LEVEL)) {
            return;
        }
        // Outil insuffisant (diamant à la main nue…) : vanilla ne donne rien,
        // l'anneau non plus.
        if (block.getDrops(tool).isEmpty()) return;

        final net.minecraft.server.v1_8_R3.World world =
                ((org.bukkit.craftbukkit.v1_8_R3.CraftWorld) block.getWorld()).getHandle();
        final net.minecraft.server.v1_8_R3.BlockPosition pos =
                new net.minecraft.server.v1_8_R3.BlockPosition(block.getX(), block.getY(), block.getZ());
        // État capturé AVANT la casse : au tick suivant la case est de l'air.
        final net.minecraft.server.v1_8_R3.IBlockData state = world.getType(pos);

        final Location key = block.getLocation();
        final int[] suppressed = new int[1];
        replacing.put(key, suppressed);

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                replacing.remove(key);
                // Tous les blocs de la liste donnent au moins un objet quand la
                // casse a lieu : zéro objet supprimé veut dire que le bloc n'a
                // pas été cassé (annulation tardive) — on ne donne rien.
                // Plus sûr que de comparer le type du bloc, qu'un replantage
                // automatique aurait pu remettre à l'identique.
                if (suppressed[0] == 0) return;
                state.getBlock().dropNaturally(world, pos, state, 1.0F, RING_FORTUNE_LEVEL);
            }
        });
    }

    /**
     * Supprime les drops vanilla d'une case dont la casse est rejouée en
     * Fortune IV. Ils apparaissent dans la foulée de l'événement de casse, sur
     * la même case : rien d'autre n'y fait apparaître d'objet dans ce tick.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (replacing.isEmpty()) return;
        int[] suppressed = replacing.get(event.getLocation().getBlock().getLocation());
        if (suppressed == null) return;
        event.setCancelled(true);
        suppressed[0]++;
    }

    @SuppressWarnings("deprecation")
    private static boolean isFortuneBlock(Block block) {
        if (FORTUNE_BLOCKS.contains(block.getType())) return true;
        int id = block.getTypeId();
        return id == RUBY_ORE_ID || id == COBALT_ORE_ID || id == RANDOM_ORE_ID;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (RingEffects.hasRing((Player) event.getEntity(), RingEffects.NECKLACE_OF_FALL)) {
            event.setCancelled(true);
        }
    }
}
