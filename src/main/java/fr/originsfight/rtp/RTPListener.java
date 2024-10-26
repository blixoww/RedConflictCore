package fr.originsfight.rtp;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class RTPListener implements Listener {
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (RTP.instance().getBukkitTask().containsKey(player)) {
            RTP.instance().getBukkitTask().get(player).cancel();
            RTP.instance().getBukkitTask().remove(player);
            player.sendMessage("§cTéléportation annulée.");
        }
    }
}
