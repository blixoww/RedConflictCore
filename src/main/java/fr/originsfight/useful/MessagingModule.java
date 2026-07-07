package fr.originsfight.useful;

import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module messagerie privée : /msg, /r et /msgspy, trois commandes fines
 * autour du {@link PrivateMessageService} qui garde l'état des conversations.
 */
public class MessagingModule implements Module {

    private final JavaPlugin plugin;

    public MessagingModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Messaging";
    }

    @Override
    public void enable() {
        PrivateMessageService messages = new PrivateMessageService();
        CommandRegistrar commands = new CommandRegistrar(plugin);
        commands.register("msg", new MsgCommand(plugin, messages));
        commands.register("r", new ReplyCommand(plugin, messages));
        commands.register("msgspy", new MsgSpyCommand(plugin, messages));
    }
}
