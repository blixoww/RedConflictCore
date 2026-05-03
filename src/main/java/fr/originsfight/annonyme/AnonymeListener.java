package fr.originsfight.annonyme;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class AnonymeListener implements Listener {

    private final AnonymeManager anonymeManager;

    public AnonymeListener(AnonymeManager anonymeManager) {
        this.anonymeManager = anonymeManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        anonymeManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        anonymeManager.onPlayerQuit(event.getPlayer());
    }
}
