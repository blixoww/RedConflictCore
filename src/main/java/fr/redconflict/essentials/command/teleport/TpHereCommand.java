package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /tphere &lt;joueur&gt; — amène un joueur à soi, immédiatement.
 *
 * <p>C'est le pendant staff de /tpahere : ni demande, ni acceptation, ni délai
 * d'attente, ni cooldown. Les trois existent pour encadrer un pouvoir entre
 * joueurs — on ne se fait pas déplacer sans avoir dit oui, et on ne fuit pas un
 * combat par téléportation. Aucune de ces raisons ne s'applique à un membre du
 * staff qui a besoin d'amener quelqu'un devant lui pour une vérification.
 *
 * <p>La position de départ du joueur déplacé est enregistrée pour /back : il
 * peut donc revenir d'où il vient sans qu'on ait à noter ses coordonnées.
 */
public class TpHereCommand extends EssCommand {

    private final TeleportService teleports;

    public TpHereCommand(CommandEnvironment env, TeleportService teleports) {
        super(env, "tphere", true, false); // aucun cooldown à armer
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player staff = (Player) sender;

        if (args.length < 1) {
            staff.sendMessage(Text.error("Usage : §f/tphere <joueur>"));
            return false;
        }
        Player target = findOnline(sender, args[0]);
        if (target == null) {
            return false;
        }
        if (target.equals(staff)) {
            staff.sendMessage(Text.error("Vous êtes déjà là."));
            return false;
        }

        teleports.teleportNow(target, staff.getLocation());
        target.sendMessage(Text.info("§f" + staff.getName() + " §7vous a téléporté à lui."));
        staff.sendMessage(Text.info("§f" + target.getName() + " §7a été téléporté à vous."));
        return true;
    }
}
