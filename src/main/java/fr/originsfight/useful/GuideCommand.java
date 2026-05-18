package fr.originsfight.useful;

import fr.originsfight.RC;
import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GuideCommand implements CommandExecutor {

    private static final String CHANNEL_S2C = "CUSTOM:S2C";
    /** PacketId.GUIDE_OPEN = 0xC0 — ouvre GuiCraftGuide côté client modifié. */
    private static final int GUIDE_OPEN = 0xC0;

    private final OriginsFightCore plugin;

    public GuideCommand(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player p = (Player) sender;

        // Envoie le packet 0xC0 pour ouvrir le GuiCraftGuide sur le client modifié.
        try {
            byte[] pkt = PacketBuilder.create(GUIDE_OPEN).build();
            p.sendPluginMessage(plugin, CHANNEL_S2C, pkt);
        } catch (Exception e) {
            plugin.getLogger().warning("[Guide] Impossible d'envoyer le packet GUIDE_OPEN : " + e.getMessage());
        }

        return true;
    }
}
