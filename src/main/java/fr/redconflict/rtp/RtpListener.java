package fr.redconflict.rtp;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Annule le préavis de /rtp quand le joueur quitte son bloc — et uniquement
 * dans ce cas.
 *
 * <p>Auparavant le moindre {@link PlayerMoveEvent} annulait, or Bukkit en émet
 * un à chaque mouvement de souris : regarder autour de soi pendant le préavis
 * suffisait à tout annuler. Le pré-filtre sur les coordonnées de bloc écarte la
 * rotation pure, exactement comme le fait {@code TeleportGuardListener} pour
 * /spawn.
 */
public class RtpListener implements Listener {

    private final RtpService rtp;

    public RtpListener(RtpService rtp) {
        this.rtp = rtp;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null
                || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // rotation de la vue : le joueur n'a pas bougé
        }
        rtp.handleMove(event.getPlayer(), to);
    }

    /** Un joueur déconnecté n'a plus de téléportation en attente. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        rtp.cancelSilently(event.getPlayer().getUniqueId());
    }
}
