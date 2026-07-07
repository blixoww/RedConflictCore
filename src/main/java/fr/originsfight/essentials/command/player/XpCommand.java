package fr.originsfight.essentials.command.player;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /xp &lt;give|set&gt; &lt;joueur&gt; &lt;montant&gt; — gère l'expérience.
 * Le montant est en points ; suffixe {@code l} pour des niveaux
 * (ex. {@code /xp give Steve 30l}), comme Essentials.
 */
public class XpCommand extends EssCommand {

    public XpCommand(CommandEnvironment env) {
        super(env, "xp", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.error("Usage : /xp <give|set> <joueur> <montant>[l]"));
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("give") && !action.equals("set")) {
            sender.sendMessage(Text.error("Usage : /xp <give|set> <joueur> <montant>[l]"));
            return false;
        }

        Player target = findOnline(sender, args[1]);
        if (target == null) return false;

        String raw = args[2].toLowerCase(Locale.ROOT);
        boolean levels = raw.endsWith("l");
        Integer amount = parseInt(sender, levels ? raw.substring(0, raw.length() - 1) : raw);
        if (amount == null) return false;
        if (amount < 0) {
            sender.sendMessage(Text.error("Le montant doit être positif."));
            return false;
        }

        if (levels) {
            target.setLevel(action.equals("give") ? target.getLevel() + amount : amount);
        } else if (action.equals("give")) {
            target.giveExp(amount);
        } else {
            // set en points : remise à zéro puis attribution (gère la courbe des niveaux).
            target.setTotalExperience(0);
            target.setLevel(0);
            target.setExp(0f);
            target.giveExp(amount);
        }

        String what = "§f" + amount + (levels ? " niveau(x)" : " points d'XP");
        sender.sendMessage(Text.success((action.equals("give") ? "Donné " : "XP réglée à ")
                + what + " §a" + (action.equals("give") ? "à" : "pour") + " §f" + target.getName() + "§a."));
        if (sender != target) {
            target.sendMessage(Text.info("Votre expérience a été mise à jour."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String action : Arrays.asList("give", "set")) {
                if (action.startsWith(args[0].toLowerCase(Locale.ROOT))) matches.add(action);
            }
            return matches;
        }
        return null; // complétion joueurs standard
    }
}
