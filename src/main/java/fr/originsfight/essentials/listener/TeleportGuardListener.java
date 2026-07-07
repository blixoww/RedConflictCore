package fr.originsfight.essentials.listener;

import fr.originsfight.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Annule les téléportations à délai d'attente si le joueur bouge (changement
 * de bloc) ou subit des dégâts pendant le warmup. La logique vit dans
 * {@link TeleportService} ; ce listener ne fait que router les événements.
 */
public class TeleportGuardListener implements Listener {

    private final TeleportService teleports;

    public TeleportGuardListener(TeleportService teleports) {
        this.teleports = teleports;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        // Pré-filtre : la rotation seule (même bloc) ne compte pas comme mouvement.
        if (to == null
                || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        teleports.handleMove(event.getPlayer(), to);
    }

    /** ignoreCancelled : les dégâts annulés (god, amis...) n'interrompent pas le warmup. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            teleports.handleDamage((Player) event.getEntity());
        }
    }
}
