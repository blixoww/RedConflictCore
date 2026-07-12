package fr.redconflict.rtp;

import fr.redconflict.core.text.RC;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Annule le préavis de /rtp dès que le joueur bouge. */
public class RtpListener implements Listener {

    private final RtpService rtp;

    public RtpListener(RtpService rtp) {
        this.rtp = rtp;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (rtp.cancelPending(event.getPlayer())) {
            event.getPlayer().sendMessage(RC.RTP_CANCELLED);
        }
    }
}
