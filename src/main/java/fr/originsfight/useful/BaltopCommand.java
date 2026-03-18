package fr.originsfight.useful;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * /baltop [page] — Classement des richesses en chat (comme /ks top).
 * Prérequis : Vault + plugin économie.
 */
public class BaltopCommand implements CommandExecutor, TabCompleter {

    private static final int PER_PAGE = 10;

    private net.milkbowl.vault.economy.Economy economy = null;
    private boolean vaultChecked = false;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player viewer = (Player) sender;

        if (!setupEconomy()) {
            viewer.sendMessage(RC.PRE + "§cVault ou un plugin economie n'est pas installe.");
            return true;
        }

        int page = 1;
        if (args.length >= 1) {
            try { page = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        final int finalPage = page;
        viewer.sendMessage(RC.PRE + "§7Chargement du classement...");

        // Chargement asynchrone car getOfflinePlayers() peut être lent
        Bukkit.getScheduler().runTaskAsynchronously(
            OriginsFightCore.getInstance(), () -> {
                final List<BaltopEntry> data = buildData();
                Bukkit.getScheduler().runTask(
                    OriginsFightCore.getInstance(), new Runnable() {
                    public void run() { showPage(viewer, data, finalPage); }
                });
            });
        return true;
    }

    private boolean setupEconomy() {
        if (vaultChecked) return economy != null;
        vaultChecked = true;
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        try {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp == null) return false;
            economy = rsp.getProvider();
        } catch (NoClassDefFoundError e) { return false; }
        return economy != null;
    }

    private List<BaltopEntry> buildData() {
        List<BaltopEntry> list = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null) continue;
            if (!economy.hasAccount(op)) continue;
            double bal = economy.getBalance(op);
            if (bal <= 0) continue;
            list.add(new BaltopEntry(op.getName(), bal));
        }
        list.sort(new Comparator<BaltopEntry>() {
            public int compare(BaltopEntry a, BaltopEntry b) { return Double.compare(b.balance, a.balance); }
        });
        return list;
    }

    private void showPage(Player viewer, List<BaltopEntry> data, int page) {
        if (data.isEmpty()) { viewer.sendMessage(RC.PRE + "§cAucun joueur avec un solde."); return; }

        int totalPages = Math.max(1, (int) Math.ceil((double) data.size() / PER_PAGE));
        page = Math.max(1, Math.min(page, totalPages));

        int from = (page - 1) * PER_PAGE;
        int to   = Math.min(from + PER_PAGE, data.size());

        DecimalFormat df = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));

        viewer.sendMessage(RC.SEP);
        viewer.sendMessage("§6§l  Baltop §8| §7Page " + page + "/" + totalPages);
        viewer.sendMessage(RC.SEP);

        for (int i = from; i < to; i++) {
            int rank = i + 1;
            BaltopEntry e = data.get(i);

            String medal;
            if (rank == 1)      medal = "§6#1";
            else if (rank == 2) medal = "§f#2";
            else if (rank == 3) medal = "§c#3";
            else                medal = "§7#" + rank;

            viewer.sendMessage("  " + medal + " §f" + e.name + " §8| §a" + df.format(e.balance) + " $");
        }

        viewer.sendMessage(RC.SEP);

        // Position du viewer dans le classement
        String viewerName = viewer.getName();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).name.equalsIgnoreCase(viewerName)) {
                viewer.sendMessage("  §7Votre position §8: §e#" + (i + 1) + " §8| §a" + df.format(data.get(i).balance) + " $");
                break;
            }
        }
        if (totalPages > 1)
            viewer.sendMessage("  §7Page suivante §8: §f/baltop " + (page + 1));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { return new ArrayList<>(); }

    private static class BaltopEntry {
        final String name;
        final double balance;
        BaltopEntry(String name, double balance) { this.name = name; this.balance = balance; }
    }
}
