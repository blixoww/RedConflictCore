package fr.redconflict.rtp;

import fr.redconflict.core.command.CoreCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /rtp — téléportation aléatoire (préavis et cooldown gérés par {@link RtpService}). */
public class RtpCommand extends CoreCommand {

    private final RtpService rtp;

    public RtpCommand(JavaPlugin plugin, RtpService rtp) {
        super(plugin, "rtp", true);
        this.rtp = rtp;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        rtp.request((Player) sender);
    }
}
