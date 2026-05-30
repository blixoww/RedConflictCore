package fr.originsfight.job;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Donne de l'XP Mineur quand un joueur casse un minerai.
 */
public class JobMinerListener implements Listener {

    private final JobManager manager;
    private final JobConfig  config;

    public JobMinerListener(JobManager manager, JobConfig config) {
        this.manager = manager;
        this.config  = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        Material mat  = event.getBlock().getType();
        int      data = event.getBlock().getData();

        // Clé "MATERIAL" ou "MATERIAL:META"
        String keySimple = mat.name();
        String keyMeta   = mat.name() + ":" + data;

        int level = manager.getLevel(player, JobType.MINER);
        int xp = config.getMinerXp(keyMeta, level);
        if (xp == 0) xp = config.getMinerXp(keySimple, level);
        if (xp > 0) manager.giveXp(player, JobType.MINER, xp);
    }
}


