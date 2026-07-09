package fr.redconflict.hdv;

import fr.redconflict.RedConflictCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public class HdvLoginListener implements Listener {
    private final RedConflictCore plugin;

    public HdvLoginListener(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        HdvManager manager = HdvManager.getInstance();
        if (manager == null)
            return;
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline())
                manager.sendPlayerBalance(player);
        }, 40L);
    }
}
