package fr.originsfight.essentials.command.social;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * /near [rayon] — joueurs proches, triés par distance. Le rayon est borné par
 * {@code near.max-radius} (sauf {@code redconflict.near.unlimited}) et les
 * joueurs vanish restent invisibles ({@link Player#canSee}).
 */
public class NearCommand extends EssCommand {

    private static final String UNLIMITED_PERMISSION = "redconflict.near.unlimited";

    public NearCommand(CommandEnvironment env) {
        super(env, "near", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        int radius = env.getConfig().nearDefaultRadius();
        if (args.length >= 1) {
            Integer parsed = parseInt(player, args[0]);
            if (parsed == null) return false;
            radius = parsed;
        }
        int max = env.getConfig().nearMaxRadius();
        if (radius > max && !player.hasPermission(UNLIMITED_PERMISSION)) {
            radius = max;
        }
        if (radius <= 0) {
            player.sendMessage(Text.error("Le rayon doit être positif."));
            return false;
        }

        final double radiusSquared = (double) radius * radius;
        List<Player> nearby = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (other == player || !player.canSee(other)) continue;
            if (other.getLocation().distanceSquared(player.getLocation()) <= radiusSquared) {
                nearby.add(other);
            }
        }

        if (nearby.isEmpty()) {
            player.sendMessage(Text.info("Aucun joueur dans un rayon de §f" + radius + " §7blocs."));
            return true;
        }

        Collections.sort(nearby, new Comparator<Player>() {
            @Override
            public int compare(Player a, Player b) {
                return Double.compare(a.getLocation().distanceSquared(player.getLocation()),
                        b.getLocation().distanceSquared(player.getLocation()));
            }
        });

        StringBuilder list = new StringBuilder();
        for (Player other : nearby) {
            if (list.length() > 0) list.append("§7, ");
            long distance = Math.round(other.getLocation().distance(player.getLocation()));
            list.append("§f").append(other.getName()).append(" §7(").append(distance).append("m)");
        }
        player.sendMessage(Text.info("Joueurs proches (§f" + nearby.size() + "§7) : " + list));
        return true;
    }
}
