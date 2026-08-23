package fr.redconflict.pb;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.site.SiteDatabase;

/**
 * Module PB (Points Boutique) : solde, journalisation des mouvements,
 * alertes staff au-delà du seuil et commande /pb.
 *
 * <p>Choisit ici, et une seule fois, <b>où vit le solde</b> :
 *
 * <ul>
 *   <li>{@code site} (défaut) — {@code rc_pb} dans la base d'Azuriom. Un seul
 *       exemplaire, dépensable en jeu comme sur le site sans pouvoir l'être deux
 *       fois. Demande que le pont soit ouvert.</li>
 *   <li>{@code h2} — {@code player_profiles.pb}, comportement d'avant la
 *       boutique web. La boutique du site ne fonctionne pas dans ce mode : elle
 *       n'a aucun accès à H2.</li>
 * </ul>
 *
 * <p>Le repli vers H2 est <b>explicite</b>. Si {@code site} est demandé et que le
 * pont est fermé, on refuse de démarrer le module plutôt que de basculer en
 * silence : deux serveurs qui ne s'accordent pas sur l'emplacement du solde, ce
 * sont deux soldes, et personne ne s'en aperçoit avant la première réclamation.
 */
public class PBModule implements Module {

    private final RedConflictCore plugin;
    private final PlayerDatabase playerDatabase;
    private final SiteDatabase siteDatabase;

    private PBManager manager;
    private StaffAlertManager staffAlerts;

    public PBModule(RedConflictCore plugin, PlayerDatabase playerDatabase, SiteDatabase siteDatabase) {
        this.plugin = plugin;
        this.playerDatabase = playerDatabase;
        this.siteDatabase = siteDatabase;
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

        PBLedger ledger = chooseLedger();

        PBLogger logger = new PBLogger(plugin);
        this.staffAlerts = new StaffAlertManager(plugin);
        this.manager = new PBManager(plugin, ledger, logger, staffAlerts);
        new CommandRegistrar(plugin).register("pb", new PBCommand(plugin, manager));
        plugin.getLogger().info("[PB] Système Points Boutique initialisé (stockage : "
                + ledger.getName() + ", seuil alerte staff : " + staffAlerts.getThreshold() + " PB).");
    }

    private PBLedger chooseLedger() throws Exception {
        String mode = plugin.getConfig().getString("pb.ledger", "site");

        if ("h2".equalsIgnoreCase(mode)) {
            plugin.getLogger().warning("[PB] Solde stocké dans H2 : la boutique du site "
                    + "ne pourra pas débiter les joueurs.");
            return new H2PBLedger(playerDatabase);
        }

        if (siteDatabase == null || !siteDatabase.isAvailable()) {
            throw new IllegalStateException(
                    "pb.ledger vaut « site » mais le pont vers la base du site est fermé. "
                  + "Vérifie site.enabled / site.password, ou bascule pb.ledger sur « h2 ».");
        }
        return new SitePBLedger(plugin, siteDatabase);
    }

    /** Requis par la boutique PB, l'HDV, le trade et les handlers de packets. */
    public PBManager getManager() {
        return manager;
    }

    public StaffAlertManager getStaffAlerts() {
        return staffAlerts;
    }
}
