package fr.redconflict.pb;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.data.PlayerDatabase;

/**
 * Module PB (Points Boutique) : solde, journalisation des mouvements,
 * alertes staff au-delà du seuil et commande /pb.
 */
public class PBModule implements Module {

    private final RedConflictCore plugin;
    private final PlayerDatabase playerDatabase;

    private PBManager manager;
    private StaffAlertManager staffAlerts;

    public PBModule(RedConflictCore plugin, PlayerDatabase playerDatabase) {
        this.plugin = plugin;
        this.playerDatabase = playerDatabase;
    }

    @Override
    public String getName() {
        return "PB";
    }

    @Override
    public void enable() throws Exception {
        if (playerDatabase == null) {
            throw new IllegalStateException("Base joueurs indisponible — PB désactivé");
        }
        PBLogger logger = new PBLogger(plugin);
        this.staffAlerts = new StaffAlertManager(plugin);
        this.manager = new PBManager(plugin, playerDatabase, logger, staffAlerts);
        new CommandRegistrar(plugin).register("pb", new PBCommand(plugin, manager));
        plugin.getLogger().info("[PB] Système Points Boutique initialisé (seuil alerte staff : "
                + staffAlerts.getThreshold() + " PB).");
    }

    /** Requis par la boutique PB, l'HDV, le trade et les handlers de packets. */
    public PBManager getManager() {
        return manager;
    }

    public StaffAlertManager getStaffAlerts() {
        return staffAlerts;
    }
}
