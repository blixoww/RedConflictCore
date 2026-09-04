package fr.redconflict.bottlexp;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /bottlexp [niveaux] — embouteille de l'XP dans une fiole.
 *
 * <p>Sans argument, embouteille tous les niveaux du joueur ; avec un argument,
 * seulement la quantité demandée (le reste est conservé). Dans les deux cas la
 * fiole doit contenir au moins {@link #MIN_LEVEL} niveaux, pour éviter le spam
 * de fioles à 1 niveau.
 */
public class BottleXpCommand extends CoreCommand {

    private static final int MIN_LEVEL = 10;

    public BottleXpCommand(JavaPlugin plugin) {
        super(plugin, "bottlexp", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        int owned = player.getLevel();

        if (owned < MIN_LEVEL) {
            player.sendMessage(Text.fmt(RC.BXP_NOT_ENOUGH, owned));
            return;
        }

        int levels = owned;
        if (args.length > 0) {
            Long asked = parsePositiveLong(sender, args[0]);
            if (asked == null) {
                return;
            }
            if (asked < MIN_LEVEL) {
                player.sendMessage(Text.fmt(RC.BXP_MIN_AMOUNT, MIN_LEVEL));
                return;
            }
            if (asked > owned) {
                player.sendMessage(Text.fmt(RC.BXP_ONLY_HAVE, owned));
                return;
            }
            levels = asked.intValue();
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(RC.BXP_INV_FULL);
            return;
        }

        // setLevel remet la barre de progression à zéro : on la repose telle
        // quelle après le retrait, sinon un /bottlexp partiel mangerait l'XP
        // accumulée vers le niveau suivant.
        float progress = player.getExp();
        player.setLevel(owned - levels);
        player.setExp(progress);
        player.getInventory().addItem(BottleXpItem.createBottle(levels));
        player.sendMessage(Text.fmt(RC.BXP_SUCCESS, levels));
    }

    /** Complétion : propose le total embouteillable et quelques paliers. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player)) {
            return Collections.emptyList();
        }
        int owned = ((Player) sender).getLevel();
        List<String> out = new ArrayList<String>();
        for (int step : new int[] { MIN_LEVEL, 30, 50, 100 }) {
            if (step <= owned) {
                out.add(String.valueOf(step));
            }
        }
        if (owned >= MIN_LEVEL && !out.contains(String.valueOf(owned))) {
            out.add(String.valueOf(owned));
        }
        return out;
    }
}
