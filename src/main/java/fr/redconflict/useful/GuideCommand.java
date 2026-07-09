package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /guide — ouvre le GuiCraftGuide du client moddé (packet 0xC0 sur CUSTOM:S2C). */
public class GuideCommand extends CoreCommand {

    private static final String CHANNEL_S2C = "CUSTOM:S2C";
    private static final int GUIDE_OPEN = 0xC0;

    public GuideCommand(JavaPlugin plugin) {
        super(plugin, "guide", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        try {
            player.sendPluginMessage(plugin, CHANNEL_S2C, PacketBuilder.create(GUIDE_OPEN).build());
        } catch (Exception e) {
            plugin.getLogger().warning("[Guide] Impossible d'envoyer le packet GUIDE_OPEN : " + e.getMessage());
        }
    }
}
