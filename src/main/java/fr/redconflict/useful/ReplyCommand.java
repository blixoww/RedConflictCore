package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/** /r <message> — répond au dernier interlocuteur privé. */
public class ReplyCommand extends CoreCommand {

    private final PrivateMessageService messages;

    public ReplyCommand(JavaPlugin plugin, PrivateMessageService messages) {
        super(plugin, "r", true);
        this.messages = messages;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player from = (Player) sender;
        UUID lastUuid = messages.lastInterlocutorOf(from.getUniqueId());
        if (lastUuid == null) {
            from.sendMessage(RC.MSG_NO_REPLY);
            return;
        }
        Player to = Bukkit.getPlayer(lastUuid);
        if (to == null || !to.isOnline()) {
            from.sendMessage(RC.ERR_PLAYER_NOT_FOUND);
            return;
        }
        if (args.length == 0) {
            from.sendMessage(RC.MSG_USAGE);
            return;
        }
        messages.send(from, to, String.join(" ", args));
    }
}
