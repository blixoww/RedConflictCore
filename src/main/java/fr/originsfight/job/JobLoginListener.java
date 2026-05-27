package fr.originsfight.job;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Charge les données à la connexion, sauvegarde à la déconnexion.
 */
public class JobLoginListener implements Listener {

    private final JobManager      manager;
    private final JobPacketSender sender;

    public JobLoginListener(JobManager manager, JobPacketSender sender) {
        this.manager = manager;
        this.sender  = sender;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Délai 20t pour que le plugin messaging soit prêt
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            fr.originsfight.OriginsFightCore.getInstance(), () -> {
                JobDatabase.JobData d = manager.load(player.getUniqueId());
                sender.sendJobInit(player);
                sender.sendJobData(player, d);
            }, 20L
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.unload(event.getPlayer().getUniqueId());
    }
}

