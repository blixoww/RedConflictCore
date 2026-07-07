package fr.originsfight.essentials.command.item;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.InvseeSessions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /invsee &lt;joueur&gt; — consulte l'inventaire d'un autre joueur (staff).
 * Lecture seule : les clics sont annulés par {@code InvseeListener} tant que
 * la session est ouverte.
 */
public class InvseeCommand extends EssCommand {

    private final InvseeSessions sessions;

    public InvseeCommand(CommandEnvironment env, InvseeSessions sessions) {
        super(env, "invsee", true, false);
        this.sessions = sessions;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /invsee <joueur>"));
            return false;
        }
        Player target = findOnline(player, args[0]);
        if (target == null) return false;
        if (target == player) {
            player.sendMessage(Text.error("Ouvrez plutôt votre inventaire (touche E) !"));
            return false;
        }

        sessions.open(player.getUniqueId(), target.getUniqueId());
        player.openInventory(target.getInventory());
        player.sendMessage(Text.info("Inventaire de §f" + target.getName() + " §7(lecture seule)."));
        return true;
    }
}
