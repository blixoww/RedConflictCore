package fr.originsfight.xpboost;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.job.JobManager;

/**
 * Module XpBoost : boost d'XP métiers x2 (item xp_booster). Se branche sur le
 * JobManager (progression + temps de boost restant dans les packets JOB_DATA).
 */
public class XpBoostModule implements Module {

    private final OriginsFightCore plugin;
    private final PlayerDatabase playerDatabase;
    /** Null si le module Jobs a échoué : le boost reste consommable, sans effet métier. */
    private final JobManager jobManager;

    public XpBoostModule(OriginsFightCore plugin, PlayerDatabase playerDatabase, JobManager jobManager) {
        this.plugin = plugin;
        this.playerDatabase = playerDatabase;
        this.jobManager = jobManager;
    }

    @Override
    public String getName() {
        return "XpBoost";
    }

    @Override
    public void enable() throws Exception {
        if (playerDatabase == null) {
            throw new IllegalStateException("Base joueurs indisponible — XpBoost désactivé");
        }
        XpBoostManager manager = new XpBoostManager(playerDatabase);
        if (jobManager != null) {
            jobManager.setXpBoostManager(manager);
            // Le sender métier inclut le temps de boost restant dans JOB_DATA.
            if (jobManager.getPacketSender() != null) {
                jobManager.getPacketSender().setXpBoostManager(manager);
            }
        }
        plugin.getServer().getPluginManager().registerEvents(new XpBoostListener(manager, jobManager), plugin);
    }
}
