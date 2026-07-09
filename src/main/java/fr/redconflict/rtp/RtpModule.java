package fr.redconflict.rtp;

import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/** Module RTP : téléportation aléatoire (/rtp) et l'annulation au mouvement. */
public class RtpModule implements Module {

    private final JavaPlugin plugin;

    public RtpModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Rtp";
    }

    @Override
    public void enable() {
        RtpService rtp = new RtpService(plugin);
        new CommandRegistrar(plugin).register("rtp", new RtpCommand(plugin, rtp));
        plugin.getServer().getPluginManager().registerEvents(new RtpListener(rtp), plugin);
    }
}
