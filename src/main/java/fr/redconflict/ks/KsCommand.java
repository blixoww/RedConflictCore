package fr.redconflict.ks;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import fr.redconflict.data.PlayerDatabase;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * /ks [joueur] — stats personnelles (kills, morts, ratio, temps de jeu) et
 * rang dans le classement ; /ks top — top 10 par kills.
 */
public class KsCommand extends CoreCommand {

    private final PlayerDatabase db;

    public KsCommand(JavaPlugin plugin, PlayerDatabase db) {
        super(plugin, "ks", true);
        this.db = db;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player viewer = (Player) sender;
        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            showTop(viewer);
            return;
        }
        Player target = viewer;
        if (args.length >= 1) {
            target = findOnline(viewer, args[0]);
            if (target == null) {
                return;
            }
        }
        showStats(viewer, target);
    }

    private void showStats(Player viewer, Player target) {
        PlayerDatabase.KsStats stats = db.getStats(target.getUniqueId());
        if (stats == null) {
            viewer.sendMessage(RC.PRE + "§cAucune statistique trouvée pour §f" + target.getName() + "§c.");
            return;
        }

        // Ajoute la session en cours au temps de jeu persisté.
        long sessionSec = 0;
        Long joinTime = KsListener.getJoinTime(target.getUniqueId());
        if (joinTime != null) {
            sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
        }
        PlayerDatabase.KsStats live = new PlayerDatabase.KsStats(
                stats.name, stats.kills, stats.deaths, stats.playtimeSeconds + sessionSec);

        int rank = db.getRank(target.getUniqueId());
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§c§l  " + live.name + " §8| §7Classement " + (rank > 0 ? "§e#" + rank : "§8N/A"));
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("  §7Kills         §8| §a" + live.kills);
        viewer.sendMessage("  §7Morts         §8| §c" + live.deaths);
        viewer.sendMessage("  §7Ratio K/D     §8| §e" + live.ratio());
        viewer.sendMessage("  §7Temps de jeu  §8| §b" + live.formattedPlaytime());
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("  §7Tapez §f/ks top §7pour le classement global.");
    }

    private void showTop(Player viewer) {
        List<PlayerDatabase.KsStats> top = db.getTopKs(10);
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§c§l  Classement KS §8| §7Top " + top.size());
        viewer.sendMessage(RC.SEP);
        if (top.isEmpty()) {
            viewer.sendMessage("  §7Aucun joueur classé pour l'instant.");
        } else {
            for (int i = 0; i < top.size(); i++) {
                PlayerDatabase.KsStats stats = top.get(i);
                viewer.sendMessage("  " + medal(i + 1) + " §f" + stats.name
                        + " §8| §aK §f" + stats.kills
                        + " §8| §cM §f" + stats.deaths
                        + " §8| §eKD §f" + stats.ratio());
            }
        }
        viewer.sendMessage(RC.SEP);
        int myRank = db.getRank(viewer.getUniqueId());
        if (myRank > 0) {
            viewer.sendMessage("  §7Votre position §8: §e#" + myRank);
        }
    }

    private static String medal(int rank) {
        switch (rank) {
            case 1: return "§6#1";
            case 2: return "§7#2";
            case 3: return "§c#3";
            default: return "§8#" + rank;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("top");
            for (Player p : Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
        }
        return list;
    }
}
