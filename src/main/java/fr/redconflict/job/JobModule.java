package fr.redconflict.job;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.db.Database;

/**
 * Module métiers (Mineur/Agriculteur/Artisan) : persistance H2, listeners
 * de progression par métier, classements snapshotés et commande /metier.
 * L'UI est rendue côté client moddé via les canaux JOB dédiés.
 */
public class JobModule implements Module {

    private final RedConflictCore plugin;
    private final Database database;

    private JobDatabase jobDatabase;
    private JobManager jobManager;
    private JobTopManager jobTopManager;

    public JobModule(RedConflictCore plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "Jobs";
    }

    @Override
    public void enable() throws Exception {
        this.jobDatabase = new JobDatabase(database);
        if (!jobDatabase.connect()) {
            throw new IllegalStateException("Impossible d'initialiser la base de données jobs");
        }
        JobConfig jobConfig = new JobConfig(plugin);
        this.jobManager = new JobManager(plugin, jobDatabase, jobConfig);
        JobPacketSender jobSender = new JobPacketSender(plugin, jobConfig, jobDatabase);
        jobManager.setPacketSender(jobSender);

        // Snapshot des classements : recalcul au démarrage, toutes les 24 h et via /metier topupdate.
        this.jobTopManager = new JobTopManager(plugin, jobDatabase);
        jobManager.setTopManager(jobTopManager);
        jobTopManager.init();

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, JobServerHandler.CHANNEL_C2S, plugin.getChannelGuard().wrap(new JobServerHandler(plugin, jobManager, jobSender)));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, JobPacketSender.CHANNEL_S2C);

        plugin.getServer().getPluginManager().registerEvents(new JobLoginListener(plugin, jobManager, jobSender), plugin);
        plugin.getServer().getPluginManager().registerEvents(new JobMinerListener(jobManager, jobConfig), plugin);
        plugin.getServer().getPluginManager().registerEvents(new JobFarmerListener(jobManager, jobConfig), plugin);
        plugin.getServer().getPluginManager().registerEvents(new JobArtisanListener(jobManager, jobConfig), plugin);

        new CommandRegistrar(plugin).register("metier", new JobCommand(plugin, jobManager, jobSender));
    }

    @Override
    public void disable() {
        if (jobTopManager != null) {
            jobTopManager.shutdown();
        }
        if (jobManager != null) {
            jobManager.saveAll();
            jobDatabase.disconnect();
        }
    }

    /** Requis par le module XpBoost (boost d'XP métiers) et /profil. */
    public JobManager getJobManager() {
        return jobManager;
    }
}
