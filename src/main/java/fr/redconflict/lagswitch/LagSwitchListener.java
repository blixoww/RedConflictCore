package fr.redconflict.lagswitch;

import fr.redconflict.RedConflictCore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;

/**
 * Listener qui applique les restrictions anti lag-switch.
 *
 * Pendant le lag-switch (ou la grace-period) :
 *  - Block-break annulé + bloc restauré côté client
 *  - Block-place annulé
 *  - Mouvements bloqués (rubber-band géré par LagSwitchManager)
 *  - Dégâts infligés annulés
 *  - Commandes sensibles bloquées
 *  - Nettoyage à la déconnexion
 */
public class LagSwitchListener implements Listener {

    private final RedConflictCore plugin;
    private final LagSwitchManager manager;

    public LagSwitchListener(RedConflictCore plugin, LagSwitchManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ── Block Break ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        if (manager.isRestricted(player)) {
            event.setCancelled(true);
            // Forcer la re-synchronisation du bloc côté client
            final Block block = event.getBlock();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (block.getType() != Material.AIR) {
                    player.sendBlockChange(block.getLocation(),
                            block.getType(), block.getData());
                }
            });

            if (manager.isLagging(player)) {
                player.sendMessage("§8[§c§lAntiLag§8] §cVous ne pouvez pas casser de blocs "
                        + "pendant une instabilité de connexion.");
            } else {
                // Grace period : message moins alarmant
                player.sendMessage("§8[§c§lAntiLag§8] §eConnexion récupérée, "
                        + "veuillez patienter quelques secondes.");
            }
        }
    }

    // ── Block Place ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        if (manager.isRestricted(player)) {
            event.setCancelled(true);
            player.updateInventory();
        }
    }

    // ── Mouvements ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        if (manager.isLagging(player)) {
            // Annuler tout déplacement horizontal (autoriser les changements de
            // direction de la tête pour éviter le spam d'events)
            Location from = event.getFrom();
            Location to   = event.getTo();
            if (to == null) return;

            // Ne rubber-band que si le joueur a vraiment bougé (pas juste tourné la tête)
            if (from.getBlockX() != to.getBlockX()
                    || from.getBlockY() != to.getBlockY()
                    || from.getBlockZ() != to.getBlockZ()) {
                event.setTo(from);
            }
        }
    }

    // ── Dégâts PvP ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        if (attacker.isOp()) return;

        if (manager.isRestricted(attacker)) {
            event.setCancelled(true);
        }
    }

    // ── Interaction / Utilisation d'items ─────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        // Bloquer seulement les interactions avec des blocs (pour éviter
        // de gêner les inventaires / GUI pendant la grace-period)
        if (manager.isLagging(player)) {
            event.setCancelled(true);
        }
    }

    // ── Drop d'items ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        if (manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    // ── Teleportation (éviter d'exploiter un TP pendant la grace) ────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        // Autoriser les téléportations serveur (PLUGIN, COMMAND) mais pas les
        // enderperles ou portails pendant le lag
        if (manager.isLagging(player)) {
            if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                    || cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                event.setCancelled(true);
            }
        }
    }

    // ── Déconnexion ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.resetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        manager.resetPlayer(event.getPlayer().getUniqueId());
    }

    // ── Connexion ─────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Réinitialiser l'état précédent (sécurité en cas de reconnexion rapide)
        manager.resetPlayer(event.getPlayer().getUniqueId());
    }
}


