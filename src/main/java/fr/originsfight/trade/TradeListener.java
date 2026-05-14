package fr.originsfight.trade;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class TradeListener implements Listener {

    private final TradeManager manager = TradeManager.getInstance();

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        TradeSession session = manager.getSession(player);
        if (session != null && session.isActive()) {
            manager.removeSession(session);
            session.onPlayerQuit(player);
        }
        manager.cleanupPlayer(player);
    }
}
