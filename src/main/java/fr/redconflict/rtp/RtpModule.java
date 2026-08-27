package fr.redconflict.rtp;

import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module RTP : téléportation aléatoire (/rtp), annulation au mouvement, et
 * réserve de points d'arrivée préparée en arrière-plan.
 *
 * <p>La réserve démarre avec le module : elle a ainsi de l'avance avant le
 * premier /rtp, et la commande n'a plus jamais à générer de terrain sur le
 * thread principal.
 */
public class RtpModule implements Module {

    private final JavaPlugin plugin;
    private RtpLocationPool pool;

    public RtpModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Rtp";
    }

    @Override
    public void enable() {
        this.pool = new RtpLocationPool(plugin);
        this.pool.start();

        RtpService rtp = new RtpService(plugin, pool);
        new CommandRegistrar(plugin).register("rtp", new RtpCommand(plugin, rtp));
        plugin.getServer().getPluginManager().registerEvents(new RtpListener(rtp), plugin);
    }

    @Override
    public void disable() {
        if (pool != null) {
            pool.stop();
            pool = null;
        }
    }
}
