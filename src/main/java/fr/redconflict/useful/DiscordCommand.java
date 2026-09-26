package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /discord} — le lien d'invitation du Discord, cliquable.
 *
 * <p>Lu dans {@code config.yml} ({@code discord.lien}) à chaque appel : un
 * changement d'invitation se fait sans redémarrer. La valeur par défaut est
 * celle du launcher et du menu principal du client.
 */
public class DiscordCommand extends CoreCommand {

    static final String DEFAULT_LINK = "https://discord.gg/N6s4CA84XK";

    public DiscordCommand(JavaPlugin plugin) {
        super(plugin, "discord", false);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        String lien = plugin.getConfig().getString("discord.lien", DEFAULT_LINK).trim();
        if (lien.isEmpty()) lien = DEFAULT_LINK;

        if (!(sender instanceof Player)) {
            sender.sendMessage("Discord : " + lien);
            return;
        }

        TextComponent message = new TextComponent(ChatColor.translateAlternateColorCodes('&',
                "&9&l✦ Discord &8» &f"));
        TextComponent link = new TextComponent(lien);
        link.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, lien));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Clique pour rejoindre le Discord").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        message.addExtra(link);
        ((Player) sender).spigot().sendMessage(message);
    }
}
