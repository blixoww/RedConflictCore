package fr.originsfight.loto;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import fr.originsfight.core.text.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commande /loto :
 *   - /loto <montant>      → Parier sur le loto en cours
 *   - /loto info            → Voir l'état du loto en cours
 *   - /loto next            → Voir quand le prochain loto aura lieu
 *   - /loto help            → Affiche l'aide complète
 *   - /loto start           → (Staff) Forcer le démarrage
 *   - /loto stop            → (Staff) Forcer l'arrêt + remboursement
 */
public class LotoCommand extends CoreCommand {

    private final LotoManager manager;

    public LotoCommand(JavaPlugin plugin, LotoManager manager) {
        super(plugin, "loto", true);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String sub = args[0].toLowerCase();

        // /loto help
        if (sub.equals("help")) {
            sendHelp(player);
            return;
        }

        // /loto info
        if (sub.equals("info")) {
            if (!manager.isOpen()) {
                // Afficher le temps avant le prochain loto
                long secs = manager.getSecondsUntilNext();
                if (secs > 0) {
                    player.sendMessage(Text.fmt(RC.LOTO_INFO_CLOSED_NEXT,
                            Text.duration(secs * 1000L)));
                } else {
                    player.sendMessage(RC.LOTO_INFO_CLOSED);
                }
            } else {
                long remaining = manager.getRemainingSeconds();
                player.sendMessage(Text.fmt(RC.LOTO_INFO_OPEN,
                        manager.getParticipantCount(),
                        manager.getTotalPool(),
                        Text.duration(remaining * 1000L)));
            }
            return;
        }

        // /loto next
        if (sub.equals("next")) {
            if (manager.isOpen()) {
                long remaining = manager.getRemainingSeconds();
                player.sendMessage(Text.fmt(RC.LOTO_NEXT_IN_PROGRESS,
                        Text.duration(remaining * 1000L)));
            } else {
                long secs = manager.getSecondsUntilNext();
                if (secs > 0) {
                    player.sendMessage(Text.fmt(RC.LOTO_NEXT,
                            Text.duration(secs * 1000L)));
                } else {
                    player.sendMessage(RC.LOTO_NEXT_UNKNOWN);
                }
            }
            return;
        }

        // /loto start (staff)
        if (sub.equals("start")) {
            if (!player.isOp() && !player.hasPermission("staff.loto")) {
                player.sendMessage(RC.ERR_NO_PERM);
                return;
            }
            if (manager.forceStart()) {
                player.sendMessage(RC.LOTO_FORCE_STARTED);
            } else {
                player.sendMessage(RC.LOTO_ALREADY_OPEN);
            }
            return;
        }

        // /loto stop (staff)
        if (sub.equals("stop")) {
            if (!player.isOp() && !player.hasPermission("staff.loto")) {
                player.sendMessage(RC.ERR_NO_PERM);
                return;
            }
            if (manager.forceStop()) {
                player.sendMessage(RC.LOTO_FORCE_STOPPED);
            } else {
                player.sendMessage(RC.LOTO_NOT_OPEN);
            }
            return;
        }

        // /loto <montant>
        long amount;
        try {
            amount = Long.parseLong(sub);
        } catch (NumberFormatException e) {
            player.sendMessage(RC.LOTO_USAGE);
            return;
        }

        if (amount <= 0) {
            player.sendMessage(RC.ERR_INVALID_AMOUNT);
            return;
        }

        manager.placeBet(player, amount);
    }

    private void sendHelp(Player player) {
        player.sendMessage(RC.SEP);
        player.sendMessage(RC.PRE + "§6§l\u2B50 §eCommandes du Loto §f:");
        player.sendMessage("");
        player.sendMessage("  §8| §f/loto <montant>  §7— Parier sur le loto en cours");
        player.sendMessage("  §8| §f/loto info       §7— Voir l'état du loto");
        player.sendMessage("  §8| §f/loto next       §7— Temps avant le prochain loto");
        player.sendMessage("  §8| §f/loto help       §7— Affiche cette aide");
        if (player.isOp() || player.hasPermission("staff.loto")) {
            player.sendMessage("");
            player.sendMessage("  §c§lStaff :");
            player.sendMessage("  §8| §f/loto start     §7— Forcer le démarrage d'un loto");
            player.sendMessage("  §8| §f/loto stop      §7— Arrêter le loto (remboursement)");
        }
        player.sendMessage("");
        player.sendMessage(RC.SEP);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            // Commandes joueur
            for (String s : Arrays.asList("info", "next", "help", "100", "500", "1000")) {
                if (s.startsWith(prefix)) completions.add(s);
            }
            // Commandes staff
            if (sender.isOp() || sender.hasPermission("staff.loto")) {
                if ("start".startsWith(prefix)) completions.add("start");
                if ("stop".startsWith(prefix)) completions.add("stop");
            }
        }
        return completions;
    }
}
