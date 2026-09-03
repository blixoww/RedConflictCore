package fr.redconflict.ring;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;

/**
 * Applique les effets des anneaux équipés (côté serveur).
 *
 * Événements : XP (+25%), drops à la casse (+15%), dégâts de chute (annulés).
 * Tâche périodique : Saturation (réappliquée) et Aimant (attire les items).
 */
public class RingEffectListener implements Listener {

    private static final double MAGNET_RADIUS  = 5.0D;
    private static final double XP_MULTIPLIER   = 1.25D;
    private static final double FORTUNE_CHANCE  = 0.15D;

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
     * Anneau de Fortune : une chance de doubler les drops du bloc cassé.
     *
     * <p><b>{@code ignoreCancelled = true} n'est pas décoratif ici, c'est la
     * correction d'une duplication d'objets.</b> Sans lui, ce code s'exécutait
     * aussi quand la casse avait été REFUSÉE — zone WorldGuard protégée, claim
     * RedFaction, spawn. Le bloc restait en place et ses drops tombaient quand
     * même : un coffre donnait un coffre, un enderchest donnait ses huit
     * obsidiennes, et l'opération était répétable à l'infini sur le même bloc.
     *
     * <p>La priorité MONITOR va avec : on lit un verdict déjà rendu par les
     * plugins de protection, on ne le modifie pas.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!RingEffects.hasRing(player, RingEffects.RING_OF_FORTUNE)) return;

        Collection<ItemStack> drops = event.getBlock().getDrops(player.getItemInHand());
        if (drops.isEmpty()) return;

        Location loc = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : drops) {
            if (Math.random() < FORTUNE_CHANCE) {
                loc.getWorld().dropItemNaturally(loc, drop.clone());
            }
        }
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
