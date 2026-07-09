package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.core.text.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /baltop [page] — classement des richesses en chat. Le classement est
 * construit en asynchrone (le parcours des OfflinePlayers peut être lent)
 * puis affiché sur le thread principal.
 */
public class BaltopCommand extends CoreCommand {

    private static final int PER_PAGE = 10;

    public BaltopCommand(JavaPlugin plugin) {
        super(plugin, "baltop", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player viewer = (Player) sender;

        Economy economy = VaultEconomy.get();
        if (economy == null) {
            viewer.sendMessage(RC.ERR_ECONOMY);
            return;
        }

        int requestedPage = 1;
        if (args.length >= 1) {
            try {
                requestedPage = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        final int page = requestedPage;
        viewer.sendMessage(RC.PRE + "§7Chargement du classement...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BaltopEntry> data = buildData(economy);
            Bukkit.getScheduler().runTask(plugin, () -> showPage(viewer, data, page));
        });
    }

    private List<BaltopEntry> buildData(Economy economy) {
        List<BaltopEntry> list = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || !economy.hasAccount(op)) {
                continue;
            }
            double balance = economy.getBalance(op);
            if (balance > 0) {
                list.add(new BaltopEntry(op.getName(), balance));
            }
        }
        list.sort((a, b) -> Double.compare(b.balance, a.balance));
        return list;
    }

    private void showPage(Player viewer, List<BaltopEntry> data, int page) {
        if (data.isEmpty()) {
            viewer.sendMessage(RC.PRE + "§cAucun joueur avec un solde.");
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) data.size() / PER_PAGE));
        page = Math.max(1, Math.min(page, totalPages));
        int from = (page - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, data.size());

        DecimalFormat money = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));

        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§6§l  Baltop §8| §7Page " + page + "/" + totalPages);
        viewer.sendMessage(RC.SEP);
        for (int i = from; i < to; i++) {
            BaltopEntry entry = data.get(i);
            viewer.sendMessage("  " + medal(i + 1) + " §f" + entry.name + " §8| §a" + money.format(entry.balance) + " $");
        }
        viewer.sendMessage(RC.SEP);

        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).name.equalsIgnoreCase(viewer.getName())) {
                viewer.sendMessage("  §7Votre position §8: §e#" + (i + 1)
                        + " §8| §a" + money.format(data.get(i).balance) + " $");
                break;
            }
        }
        if (totalPages > 1) {
            viewer.sendMessage("  §7Page suivante §8: §f/baltop " + (page + 1));
        }
    }

    private static String medal(int rank) {
        switch (rank) {
            case 1: return "§6#1";
            case 2: return "§f#2";
            case 3: return "§c#3";
            default: return "§7#" + rank;
        }
    }

    private static class BaltopEntry {
        final String name;
        final double balance;

        BaltopEntry(String name, double balance) {
            this.name = name;
            this.balance = balance;
        }
    }
}
