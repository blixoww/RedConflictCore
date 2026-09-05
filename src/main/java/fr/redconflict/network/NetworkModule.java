package fr.redconflict.network;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.Reloadable;
import fr.redconflict.db.Database;

/**
 * Tab-list partagé entre les serveurs de la grappe : mêmes joueurs et même total
 * sur le Faction et sur le Minage.
 *
 * <p>Installé tôt, avant le module staff : c'est {@code PlayerListManager} —
 * démarré par ce dernier — qui alimente et consomme le service.
 */
public class NetworkModule implements Module, Reloadable {

    private final RedConflictCore plugin;
    private final Database database;
    private GlobalPlayerList globalPlayerList;

    public NetworkModule(RedConflictCore plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "Network";
    }

    @Override
    public void enable() {
        if (database == null) {
            plugin.getLogger().warning("[Tab] Aucune base H2 — tab-list partagé indisponible.");
            return;
        }
        this.globalPlayerList = new GlobalPlayerList(plugin, database);
        this.globalPlayerList.start();
    }

    @Override
    public void disable() {
        if (globalPlayerList != null) globalPlayerList.stop();
    }

    /**
     * Le rechargement passe par un arrêt complet — même objet, cycle relancé.
     *
     * <p>Les lignes de présence déjà publiées et les entrées injectées dans les tabs
     * portent l'ancienne configuration (étiquettes, suffixe, cadence) : les reprendre
     * à chaud laisserait un tab hybride. On efface, puis on republie.
     *
     * <p>Le service n'est PAS remplacé : {@code PlayerListManager} en garde la
     * référence pour la vie du serveur, un nouvel objet le laisserait parler à un
     * service arrêté.
     */
    @Override
    public void reload() {
        if (globalPlayerList == null) {
            enable();
            return;
        }
        globalPlayerList.stop();
        globalPlayerList.start();
    }

    /** {@code null} tant que le module n'est pas activé. */
    public GlobalPlayerList getGlobalPlayerList() {
        return globalPlayerList;
    }
}
