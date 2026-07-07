package fr.originsfight.essentials.command.social;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.model.SeenRecord;
import fr.originsfight.essentials.service.SeenService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /seen &lt;joueur&gt; — dernière connexion d'un joueur. Les joueurs vanish
 * apparaissent hors ligne pour ceux qui ne peuvent pas les voir.
 */
public class SeenCommand extends EssCommand {

    private final SeenService seen;

    public SeenCommand(CommandEnvironment env, SeenService seen) {
        super(env, "seen", false, false);
        this.seen = seen;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Text.error("Usage : /seen <joueur>"));
            return false;
        }

        Player online = Bukkit.getPlayerExact(args[0]);
        boolean visible = online != null
                && (!(sender instanceof Player) || ((Player) sender).canSee(online));

        SeenRecord record = seen.findByName(args[0]);
        if (visible) {
            String since = record != null ? " §7(" + Text.since(record.getLastJoin()) + ")" : "";
            sender.sendMessage(Text.info("§f" + online.getName() + " §7est §aen ligne" + since + "§7."));
            return true;
        }
        if (record == null) {
            sender.sendMessage(Text.error("Joueur inconnu : §f" + args[0]));
            return false;
        }

        long lastSeen = Math.max(record.getLastQuit(), record.getLastJoin());
        sender.sendMessage(Text.info("§f" + record.getName() + " §7est §chors ligne §7— vu pour la dernière fois "
                + "§f" + Text.since(lastSeen) + "§7."));
        return true;
    }
}
