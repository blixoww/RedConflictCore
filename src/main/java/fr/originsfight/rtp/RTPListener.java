package fr.originsfight.rtp;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class RTPListener implements Listener {
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if(player.isOp()) { return; }
        if (RTP.getInstance().getBukkitTask().containsKey(player)) {
            RTP.getInstance().getBukkitTask().get(player).cancel();
            RTP.getInstance().getBukkitTask().remove(player);
            player.sendMessage("§cVous avez bougé, votre téléportation a été annulée.");
        }
    }
}
