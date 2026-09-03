package fr.redconflict.essentials.command.item;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.InvseeSessions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /invsee &lt;joueur&gt; — ouvre l'inventaire d'un autre joueur (staff).
 *
 * <p><b>Modifiable</b> : le staff doit pouvoir retirer un objet dupé ou rendre
 * un objet perdu, pas seulement regarder. Les clics ne sont plus annulés.
 *
 * <p>{@code InvseeListener} reste indispensable pour autant : il ferme la
 * fenêtre quand l'observé se déconnecte. Un inventaire modifiable ouvert sur un
 * joueur parti est un duplicateur d'objets.
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
        player.sendMessage(Text.info("Inventaire de §f" + target.getName()
                + " §7— modifiable, chaque clic s'applique immédiatement."));
        return true;
    }
}
