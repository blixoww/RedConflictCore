package fr.originsfight.useful;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /msg <joueur> <message> — envoie un message privé. */
public class MsgCommand extends CoreCommand {

    private final PrivateMessageService messages;

    public MsgCommand(JavaPlugin plugin, PrivateMessageService messages) {
        super(plugin, "msg", true);
        this.messages = messages;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player from = (Player) sender;
        if (args.length < 2) {
            from.sendMessage(RC.MSG_USAGE);
            return;
        }
        Player to = findOnline(from, args[0]);
        if (to == null) {
            return;
        }
        if (to.equals(from)) {
            from.sendMessage(RC.MSG_SELF);
            return;
        }
        messages.send(from, to, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> names = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(sender)) {
                    names.add(p.getName());
                }
            }
        }
        return names;
    }
}
