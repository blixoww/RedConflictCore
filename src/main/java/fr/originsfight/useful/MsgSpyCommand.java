package fr.originsfight.useful;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /msgspy (staff) — bascule la surveillance des messages privés. */
public class MsgSpyCommand extends CoreCommand {

    private final PrivateMessageService messages;

    public MsgSpyCommand(JavaPlugin plugin, PrivateMessageService messages) {
        super(plugin, "msgspy", true);
        this.messages = messages;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("staff.msgspy")) {
            player.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        player.sendMessage(messages.toggleSpy(player.getUniqueId()) ? RC.MSGSPY_ON : RC.MSGSPY_OFF);
    }
}
