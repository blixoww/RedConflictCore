package fr.redconflict.job;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Donne de l'XP Agriculteur pour la récolte et la plantation.
 */
public class JobFarmerListener implements Listener {

    private final JobManager manager;
    private final JobConfig  config;

    public JobFarmerListener(JobManager manager, JobConfig config) {
        this.manager = manager;
        this.config  = config;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        Material mat  = event.getBlock().getType();
        int      data = event.getBlock().getData();

        String keyMeta   = mat.name() + ":" + data;
        String keySimple = mat.name();
        int level = manager.getLevel(player, JobType.FARMER);
        int xp = config.getFarmerXp("break", keyMeta, level);
        if (xp == 0) xp = config.getFarmerXp("break", keySimple, level);
        if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        Material mat  = event.getBlock().getType();
        int      data = event.getBlock().getData();

        String keyMeta   = mat.name() + ":" + data;
        String keySimple = mat.name();
        int level = manager.getLevel(player, JobType.FARMER);
        int xp = config.getFarmerXp("place", keyMeta, level);
        if (xp == 0) xp = config.getFarmerXp("place", keySimple, level);
        if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
    }
}
