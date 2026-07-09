package fr.redconflict.staff.commands;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffDatabase;
import fr.redconflict.staff.StaffFormatter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /sanctions <joueur> [page]
 * Alias : /hist, /historique
 */
public class SanctionsCommand extends CoreCommand {

    private static final int PER_PAGE = 6; // 6 sanctions × 2 lignes = 12 lignes max
    private final StaffDatabase db;

    public SanctionsCommand(JavaPlugin plugin, StaffDatabase db) {
        super(plugin, "sanctions", false); this.db = db; }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (!isStaff(sender)) { sender.sendMessage("§cPermission insuffisante."); return; }
        if (args.length < 1) { sender.sendMessage("§cUsage : /sanctions <joueur> [page]"); return; }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        if (offline == null) { sender.sendMessage(StaffFormatter.PREFIX + "§cJoueur introuvable."); return; }

        int page = 1;
        if (args.length >= 2) {
            try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }

        List<StaffDatabase.Sanction> history = db.getHistory(offline.getUniqueId().toString());
        String name = offline.getName() != null ? offline.getName() : args[0];

        if (history.isEmpty()) {
            sender.sendMessage(StaffFormatter.PREFIX + "§7" + name + " n'a aucune sanction.");
            return;
        }

        int totalPages = (int) Math.ceil((double) history.size() / PER_PAGE);
        page = Math.max(1, Math.min(page, totalPages));
        int from = (page - 1) * PER_PAGE;
        int to   = Math.min(from + PER_PAGE, history.size());

        StaffFormatter.sendHistoryHeader(sender, name, history.size());
        if (totalPages > 1) sender.sendMessage("§8Page §7" + page + "§8/§7" + totalPages);

        for (int i = from; i < to; i++) {
            StaffFormatter.sendHistoryEntry(sender, history.get(i));
        }

        if (page < totalPages)
            sender.sendMessage("§8> §7Suite : §f/sanctions " + name + " " + (page + 1));
    }

    private boolean isStaff(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        return p.isOp() || p.hasPermission("staff.sanctions");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
        return list;
    }
}
