package fr.redconflict.essentials.command.item;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.InvseeSessions;
import fr.redconflict.essentials.service.InvseeView;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /invsee &lt;joueur&gt; — ouvre l'inventaire d'un autre joueur (staff).
 *
 * <p><b>Modifiable</b> : le staff doit pouvoir retirer un objet dupé ou rendre
 * un objet perdu, pas seulement regarder. Les clics ne sont pas annulés.
 *
 * <p><b>Armure comprise.</b> La fenêtre est un miroir de 45 cases —
 * {@link InvseeView} — et non l'inventaire lui-même : l'API ne rend que les 36
 * cases de rangement, et le casque, le plastron, les jambières et les bottes
 * n'apparaissaient nulle part. C'est justement l'armure qu'on vient vérifier
 * quand on soupçonne un objet interdit ou dupé.
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

        // Ouvrir AVANT d'enregistrer la session : openInventory ferme ce que le
        // joueur avait sous les yeux, et cette fermeture déclenche le
        // InventoryCloseEvent qui efface les sessions. Dans l'autre ordre, on
        // effacerait celle qu'on vient d'ouvrir.
        InvseeView mirror = InvseeView.of(target);
        player.openInventory(mirror.inventory());
        sessions.openMirror(player.getUniqueId(), mirror);

        player.sendMessage(Text.info("Inventaire de §f" + target.getName()
                + " §7— armure comprise, modifiable."));
        player.sendMessage(Text.info("Dernière rangée : §fcasque, plastron, jambières, bottes§7."));
        return true;
    }
}
