package fr.originsfight.ks;

import fr.originsfight.RC;
import fr.originsfight.data.PlayerDatabase;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /ks [joueur]  — Stats personnelles + rang dans le classement.
 * /ks top       — Top 10 des meilleurs joueurs par kills.
 */
public class KsCommand implements CommandExecutor, TabCompleter {

    private final PlayerDatabase db;

    public KsCommand(PlayerDatabase db) { this.db = db; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player viewer = (Player) sender;

        // /ks top
        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            showTop(viewer);
            return true;
        }

        // /ks [joueur]
        Player target = viewer;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { viewer.sendMessage(RC.PRE + "§cJoueur introuvable ou hors ligne."); return true; }
        }

        showStats(viewer, target);
        return true;
    }

    // ── Affichage des stats personnelles ──────────────────────────────────────

    private void showStats(Player viewer, Player target) {
        PlayerDatabase.KsStats stats = db.getStats(target.getUniqueId());
        if (stats == null) { viewer.sendMessage(RC.PRE + "§cAucune statistique trouvée pour §f" + target.getName() + "§c."); return; }

        long sessionSec = 0;
        Long joinTime = KsListener.getJoinTime(target.getUniqueId());
        if (joinTime != null) sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
        PlayerDatabase.KsStats live = new PlayerDatabase.KsStats(stats.name, stats.kills, stats.deaths, stats.playtimeSeconds + sessionSec);

        int rank = db.getRank(target.getUniqueId());
        String rankStr = rank > 0 ? "§e#" + rank : "§8N/A";

        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§c§l  " + live.name + " §8| §7Classement " + rankStr);
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("  §7Kills         §8| §a" + live.kills);
        viewer.sendMessage("  §7Morts         §8| §c" + live.deaths);
        viewer.sendMessage("  §7Ratio K/D     §8| §e" + live.ratio());
        viewer.sendMessage("  §7Temps de jeu  §8| §b" + live.formattedPlaytime());
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("  §7Tapez §f/ks top §7pour le classement global.");
    }

    // ── Affichage du top 10 ───────────────────────────────────────────────────

    private void showTop(Player viewer) {
        java.util.List<PlayerDatabase.KsStats> top = db.getTopKs(10);
        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§c§l  Classement KS §8| §7Top " + top.size());
        viewer.sendMessage(RC.SEP);
        if (top.isEmpty()) {
            viewer.sendMessage("  §7Aucun joueur classé pour l'instant.");
        } else {
            for (int i = 0; i < top.size(); i++) {
                PlayerDatabase.KsStats s = top.get(i);
                int pos = i + 1;
                String medal;
                if (pos == 1)      medal = "§6#1";
                else if (pos == 2) medal = "§7#2";
                else if (pos == 3) medal = "§c#3";
                else               medal = "§8#" + pos;
                viewer.sendMessage("  " + medal + " §f" + s.name
                        + " §8| §aK §f" + s.kills
                        + " §8| §cM §f" + s.deaths
                        + " §8| §eKD §f" + s.ratio());
            }
        }
        viewer.sendMessage(RC.SEP);
        // Montrer la position du viewer dans le classement
        int myRank = db.getRank(viewer.getUniqueId());
        if (myRank > 0) viewer.sendMessage("  §7Votre position §8: §e#" + myRank);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("top");
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        }
        return list;
    }
}
