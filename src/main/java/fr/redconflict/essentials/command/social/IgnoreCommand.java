package fr.redconflict.essentials.command.social;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.IgnoreService;
import fr.redconflict.essentials.service.SeenService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * /ignore &lt;joueur&gt; — masque les messages de chat d'un joueur (toggle,
 * joueurs hors ligne acceptés). Le staff avec {@code redconflict.ignore.exempt}
 * ne peut pas être ignoré.
 */
public class IgnoreCommand extends EssCommand {

    private static final String EXEMPT_PERMISSION = "redconflict.ignore.exempt";

    private final IgnoreService ignores;
    private final SeenService seen;

    public IgnoreCommand(CommandEnvironment env, IgnoreService ignores, SeenService seen) {
        super(env, "ignore", true, false);
        this.ignores = ignores;
        this.seen = seen;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /ignore <joueur>"));
            return false;
        }

        UUID targetId = seen.resolveUuid(args[0]);
        if (targetId == null) {
            player.sendMessage(Text.error("Joueur inconnu : §f" + args[0]));
            return false;
        }
        if (targetId.equals(player.getUniqueId())) {
            player.sendMessage(Text.error("Vous ne pouvez pas vous ignorer vous-même."));
            return false;
        }
        Player online = Bukkit.getPlayer(targetId);
        if (online != null && online.hasPermission(EXEMPT_PERMISSION)) {
            player.sendMessage(Text.error("Ce joueur ne peut pas être ignoré."));
            return false;
        }

        boolean ignored = ignores.toggle(player.getUniqueId(), targetId);
        String name = online != null ? online.getName() : args[0];
        if (ignored) {
            player.sendMessage(Text.success("Vous ignorez désormais §f" + name + "§a."));
        } else {
            player.sendMessage(Text.info("Vous n'ignorez plus §f" + name + "§7."));
        }
        return true;
    }
}
