package fr.originsfight.ring;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class RingLoginListener implements Listener {

    private final RingManager manager;
    private final RingPacketSender sender;

    public RingLoginListener(RingManager manager, RingPacketSender sender) {
        this.manager = manager;
        this.sender  = sender;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        manager.loadPlayer(e.getPlayer().getUniqueId());
        // Légère temporisation pour laisser le client enregistrer ses canaux
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("RedConflictCore"),
            () -> sender.sendSync(e.getPlayer()),
            40L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        manager.unloadPlayer(e.getPlayer().getUniqueId());
    }
}
