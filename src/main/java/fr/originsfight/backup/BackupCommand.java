package fr.originsfight.backup;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /dbbackup <now|next>} — gère les sauvegardes de la base (staff).
 *
 * <ul>
 *   <li>{@code now} — déclenche une sauvegarde immédiate (async) ;</li>
 *   <li>{@code next} — indique le temps restant avant la prochaine sauvegarde auto.</li>
 * </ul>
 *
 * <p>N'a d'effet que sur l'hôte H2 (le Faction). La sauvegarde s'exécute en asynchrone pour ne pas
 * bloquer le serveur ; le résultat apparaît dans la console et le dossier {@code Backup}.
 */
public class BackupCommand implements CommandExecutor, TabCompleter {

    private final OriginsFightCore plugin;
    private final BackupManager manager;

    public BackupCommand(OriginsFightCore plugin, BackupManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("redconflict.staff")) {
            sender.sendMessage(RC.ERR_NO_PERM);
            return true;
        }
        if (!plugin.getConfig().getBoolean("database.server.enabled", false)) {
            sender.sendMessage(RC.PRE + "§cLes sauvegardes se font sur le serveur Faction (hôte de la base).");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "now":
                sender.sendMessage(RC.PRE + "§7Sauvegarde lancée en arrière-plan…");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    manager.runBackup();
                    sender.sendMessage(RC.PRE + "§aSauvegarde terminée — voir le dossier Backup et la console.");
                });
                return true;

            case "next":
                long remaining = manager.getMillisUntilNext();
                if (remaining < 0L) {
                    sender.sendMessage(RC.PRE + "§cLa sauvegarde automatique n'est pas active sur ce serveur.");
                } else {
                    sender.sendMessage(RC.PRE + "§7Prochaine sauvegarde automatique dans §f" + formatDuration(remaining) + "§7.");
                }
                return true;

            default:
                sender.sendMessage(RC.PRE + "§7Usage : §f/dbbackup now §7| §f/dbbackup next");
                return true;
        }
    }

    /** Formate une durée en ms vers « 5h 12min », « 12min 30s » ou « 45s ». */
    private static String formatDuration(long millis) {
        long totalSec = millis / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return h + "h " + m + "min";
        if (m > 0) return m + "min " + s + "s";
        return s + "s";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList("now", "next")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return new ArrayList<>();
    }
}
