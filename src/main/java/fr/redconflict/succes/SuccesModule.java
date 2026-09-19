package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.db.Database;
import fr.redconflict.job.JobManager;
import fr.redconflict.job.JobType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Module Succès : catalogue YAML, avancement H2, notifications, récompenses à
 * réclamer et menu du client moddé.
 *
 * <p><b>Où vivent les compteurs.</b> La plupart sont branchés sur des
 * événements Bukkit ({@link SuccesGameplayListener}). Deux ne le sont pas et
 * passent par une relève à la minute :
 * <ul>
 *   <li>le <b>temps de jeu</b>, qui n'est pas un événement ;</li>
 *   <li>le <b>niveau de métier</b>, dont la montée est interne au module
 *       métiers. Le lire périodiquement évite d'ouvrir ce module pour y poser
 *       un crochet, au prix d'une minute de latence sur une notification — ce
 *       qui n'a aucune importance pour un succès.</li>
 * </ul>
 * L'hôtel des ventes et la bourse, eux, appellent directement le gestionnaire
 * au moment de la transaction.
 */
public class SuccesModule implements Module {

    /** Une relève par minute : assez fin pour le temps de jeu, invisible au profilage. */
    private static final long SWEEP_TICKS = 20L * 60L;

    private final RedConflictCore plugin;
    private final Database database;

    private SuccesDatabase succesDatabase;
    private SuccesManager manager;
    private int sweepTask = -1;

    public SuccesModule(RedConflictCore plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "Succes";
    }

    @Override
    public void enable() throws Exception {
        this.succesDatabase = new SuccesDatabase(database);
        if (!succesDatabase.connect()) {
            throw new IllegalStateException("Impossible d'initialiser la base de données des succès");
        }

        SuccesCatalog catalog = new SuccesCatalog(plugin);
        this.manager = new SuccesManager(plugin, succesDatabase, catalog);
        SuccesPacketSender sender = new SuccesPacketSender(plugin, catalog, manager);
        manager.setPacketSender(sender);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, SuccesServerHandler.CHANNEL_C2S,
                plugin.getChannelGuard().wrap(new SuccesServerHandler(plugin, manager, sender)));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
                plugin, SuccesPacketSender.CHANNEL_S2C);

        SuccesGameplayListener gameplay = new SuccesGameplayListener(manager);
        plugin.getServer().getPluginManager().registerEvents(gameplay, plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new SuccesLoginListener(plugin, manager, sender, gameplay), plugin);

        new CommandRegistrar(plugin).register("succes", new SuccesCommand(plugin, manager, catalog, sender));

        // Rechargement à chaud : les joueurs déjà connectés n'auront pas
        // d'événement de connexion.
        List<UUID> alreadyOnline = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            manager.load(online);
            sender.sendInit(online);
            sender.sendData(online);
            alreadyOnline.add(online.getUniqueId());
        }
        manager.refreshPlaytime(alreadyOnline);

        this.sweepTask = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, new Runnable() {
                    @Override
                    public void run() {
                        sweep();
                    }
                }, SWEEP_TICKS, SWEEP_TICKS);
    }

    @Override
    public void disable() {
        if (sweepTask != -1) {
            plugin.getServer().getScheduler().cancelTask(sweepTask);
            sweepTask = -1;
        }
        if (manager != null) {
            manager.saveAll();
            succesDatabase.disconnect();
        }
    }

    /** Temps de jeu et niveaux de métier, une fois par minute. */
    private void sweep() {
        // Temps de jeu : un relevé groupé, lu en base hors thread principal.
        List<UUID> online = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        manager.refreshPlaytime(online);

        JobManager jobs = plugin.getJobManager();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (jobs == null) continue;
            int best = 0;
            for (JobType job : new JobType[]{JobType.MINER, JobType.FARMER, JobType.ARTISAN}) {
                int level = jobs.getLevel(player, job);
                if (level > 0) {
                    manager.progress(player, SuccesTrigger.JOB_LEVEL, job.name(), level);
                }
                if (level > best) best = level;
            }
            // « ANY » : le meilleur des trois, pour un succès qui ne vise pas un
            // métier en particulier.
            if (best > 0) manager.progress(player, SuccesTrigger.JOB_LEVEL, "ANY", best);
        }
    }

    /** Requis par les points d'accroche hors module (hôtel des ventes, bourse). */
    public SuccesManager getManager() {
        return manager;
    }
}
