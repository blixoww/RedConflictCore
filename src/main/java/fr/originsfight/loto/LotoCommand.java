package fr.originsfight.loto;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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
public class LotoCommand implements CommandExecutor, TabCompleter {

    private final LotoManager manager;

    public LotoCommand(LotoManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        // /loto help
        if (sub.equals("help")) {
            sendHelp(player);
            return true;
        }

        // /loto info
        if (sub.equals("info")) {
            if (!manager.isOpen()) {
                // Afficher le temps avant le prochain loto
                long secs = manager.getSecondsUntilNext();
                if (secs > 0) {
                    player.sendMessage(RC.fmt(RC.LOTO_INFO_CLOSED_NEXT,
                            LotoManager.formatDuration(secs)));
                } else {
                    player.sendMessage(RC.LOTO_INFO_CLOSED);
                }
            } else {
                long remaining = manager.getRemainingSeconds();
                player.sendMessage(RC.fmt(RC.LOTO_INFO_OPEN,
                        manager.getParticipantCount(),
                        manager.getTotalPool(),
                        LotoManager.formatDuration(remaining)));
            }
            return true;
        }

        // /loto next
        if (sub.equals("next")) {
            if (manager.isOpen()) {
                long remaining = manager.getRemainingSeconds();
                player.sendMessage(RC.fmt(RC.LOTO_NEXT_IN_PROGRESS,
                        LotoManager.formatDuration(remaining)));
            } else {
                long secs = manager.getSecondsUntilNext();
                if (secs > 0) {
                    player.sendMessage(RC.fmt(RC.LOTO_NEXT,
                            LotoManager.formatDuration(secs)));
                } else {
                    player.sendMessage(RC.LOTO_NEXT_UNKNOWN);
                }
            }
            return true;
        }

        // /loto start (staff)
        if (sub.equals("start")) {
            if (!player.isOp() && !player.hasPermission("staff.loto")) {
                player.sendMessage(RC.ERR_NO_PERM);
                return true;
            }
            if (manager.forceStart()) {
                player.sendMessage(RC.LOTO_FORCE_STARTED);
            } else {
                player.sendMessage(RC.LOTO_ALREADY_OPEN);
            }
            return true;
        }

        // /loto stop (staff)
        if (sub.equals("stop")) {
            if (!player.isOp() && !player.hasPermission("staff.loto")) {
                player.sendMessage(RC.ERR_NO_PERM);
                return true;
            }
            if (manager.forceStop()) {
                player.sendMessage(RC.LOTO_FORCE_STOPPED);
            } else {
                player.sendMessage(RC.LOTO_NOT_OPEN);
            }
            return true;
        }

        // /loto <montant>
        long amount;
        try {
            amount = Long.parseLong(sub);
        } catch (NumberFormatException e) {
            player.sendMessage(RC.LOTO_USAGE);
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(RC.LOTO_INVALID_AMOUNT);
            return true;
        }

        manager.placeBet(player, amount);
        return true;
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
