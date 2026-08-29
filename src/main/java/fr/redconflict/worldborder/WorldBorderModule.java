package fr.redconflict.worldborder;

import fr.redconflict.core.Module;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module bordure du monde : pose la bordure vanilla sur les mondes configurés.
 *
 * <p>Le mur translucide et le voile rouge d'approche sont dessinés par le client,
 * le repoussement est fait par le serveur : le module ne fait que poser les
 * réglages au démarrage et sur chaque monde chargé ensuite. Aucune tâche
 * périodique, aucun listener de déplacement.
 */
public class WorldBorderModule implements Module {

    private final JavaPlugin plugin;

    public WorldBorderModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "WorldBorder";
    }

    @Override
    public void enable() {
        WorldBorderService service = new WorldBorderService(plugin);
        service.applyAll();
        service.warnIfRtpOutOfBounds();
        plugin.getServer().getPluginManager().registerEvents(new WorldBorderListener(service), plugin);

        if (service.isEnabled()) {
            long side = (long) service.size();
            plugin.getLogger().info("[Bordure] Active : " + side + " x " + side
                    + " blocs (soit " + (side / 2) + " blocs du centre dans chaque direction).");
        } else {
            plugin.getLogger().info("[Bordure] Desactivee : bordure par defaut rendue aux mondes.");
        }
    }
}
