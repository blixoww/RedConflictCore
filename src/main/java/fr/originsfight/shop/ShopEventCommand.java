package fr.originsfight.shop;

import fr.originsfight.shop.ShopDatabase.ShopEventRow;
import fr.originsfight.shop.ShopDatabase.ShopItem;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Commande staff /shopevent — lance/arrête manuellement des événements boursiers.
 *
 * Syntaxes :
 *   /shopevent list
 *   /shopevent reload
 *   /shopevent krach [duration_min]
 *   /shopevent inflation [duration_min]
 *   /shopevent aubaine random [duration_min]
 *   /shopevent aubaine <itemId> <up|down> <multiplier> [duration_min]
 *   /shopevent stop <id|all>
 */
public class ShopEventCommand implements CommandExecutor, TabCompleter {

    private final ShopEventManager manager;
    private final ShopDatabase database;

    public ShopEventCommand(ShopEventManager manager, ShopDatabase database) {
        this.manager = manager;
        this.database = database;
    }

    private static final String PRE = ChatColor.GOLD + "[Bourse] " + ChatColor.YELLOW;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("shop.admin")) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":     return cmdList(sender);
            case "next":     return cmdNext(sender);
            case "reload":   manager.reload(); sender.sendMessage(PRE + "Config rechargée."); return true;
            case "krach":    return cmdKrach(sender, args);
            case "inflation":return cmdInflation(sender, args);
            case "aubaine":  return cmdAubaine(sender, args);
            case "stop":     return cmdStop(sender, args);
            default:         usage(sender); return true;
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== /shopevent (staff) ===");
        s.sendMessage(ChatColor.GRAY + " /shopevent list");
        s.sendMessage(ChatColor.GRAY + " /shopevent next");
        s.sendMessage(ChatColor.GRAY + " /shopevent reload");
        s.sendMessage(ChatColor.GRAY + " /shopevent krach [duration_min]");
        s.sendMessage(ChatColor.GRAY + " /shopevent inflation [duration_min]");
        s.sendMessage(ChatColor.GRAY + " /shopevent aubaine random [duration_min]");
        s.sendMessage(ChatColor.GRAY + " /shopevent aubaine <itemId> <up|down> <multiplier> [duration_min]");
        s.sendMessage(ChatColor.GRAY + " /shopevent stop <krach|inflation|aubaine|all>");
    }

    private boolean cmdList(CommandSender s) {
        List<ShopEventRow> evs = manager.getActiveEvents();
        if (evs.isEmpty()) {
            s.sendMessage(PRE + "Aucun événement actif.");
            return true;
        }
        long now = System.currentTimeMillis() / 1000L;
        s.sendMessage(PRE + evs.size() + " événement(s) actif(s) :");
        for (ShopEventRow e : evs) {
            long left = Math.max(0, e.endTs - now);
            String hms = ShopEventManager.formatDuration((int)(left / 60L));
            s.sendMessage(ChatColor.GRAY + " §f" + e.type +
                    ChatColor.GRAY + " mb=" + String.format(Locale.ROOT, "%.2f", e.multiplierBuy) +
                    " ms=" + String.format(Locale.ROOT, "%.2f", e.multiplierSell) +
                    (e.isGlobal() ? " §7(global)" : " §7items=" + e.itemIdsCsv) +
                    ChatColor.WHITE + " — reste " + hms +
                    (e.manual ? ChatColor.DARK_GRAY + " [manuel]" : ""));
        }
        return true;
    }

    private boolean cmdNext(CommandSender s) {
        Calendar midnight = Calendar.getInstance();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long secondsUntil = Math.max(0, (midnight.getTimeInMillis() - System.currentTimeMillis()) / 1000L);
        long h = secondsUntil / 3600;
        long m = (secondsUntil % 3600) / 60;
        s.sendMessage(ChatColor.GOLD + "=== Prochain roll d'événement auto ===");
        s.sendMessage(ChatColor.GRAY + " Dans " + ChatColor.WHITE + h + "h " + m + "min"
                + ChatColor.GRAY + " (au changement de jour)");
        s.sendMessage(ChatColor.GRAY + " Probabilités configurées via " + ChatColor.WHITE + "shop_events.yml");
        return true;
    }

    private boolean cmdKrach(CommandSender s, String[] args) {
        int dur = parseDur(args, 1);
        long id = manager.launchKrach(dur, true);
        if (id == -1L) s.sendMessage(PRE + ChatColor.RED + "Échec (limite atteinte ou désactivé).");
        else s.sendMessage(PRE + "Krach lancé.");
        return true;
    }

    private boolean cmdInflation(CommandSender s, String[] args) {
        int dur = parseDur(args, 1);
        long id = manager.launchInflation(dur, true);
        if (id == -1L) s.sendMessage(PRE + ChatColor.RED + "Échec (limite atteinte ou désactivé).");
        else s.sendMessage(PRE + "Inflation lancée.");
        return true;
    }

    private boolean cmdAubaine(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(PRE + ChatColor.RED + "Usage: /shopevent aubaine random|<itemId> ...");
            return true;
        }
        if (args[1].equalsIgnoreCase("random")) {
            int dur = parseDur(args, 2);
            long id = manager.launchRandomAubaine(dur, true);
            if (id == -1L) s.sendMessage(PRE + ChatColor.RED + "Échec.");
            else s.sendMessage(PRE + "Aubaine aléatoire lancée.");
            return true;
        }
        // /shopevent aubaine <itemId> <up|down> <multiplier> [duration]
        if (args.length < 4) {
            s.sendMessage(PRE + ChatColor.RED + "Usage: /shopevent aubaine <itemId|nom> <up|down> <multiplier> [duration]");
            return true;
        }
        int itemId;
        try {
            itemId = Integer.parseInt(args[1]);
            ShopItem si = database.getItemById(itemId);
            if (si == null) { s.sendMessage(PRE + ChatColor.RED + "Item id introuvable : " + itemId); return true; }
        } catch (NumberFormatException ex) {
            // Recherche par nom partiel
            String needle = args[1].toLowerCase(Locale.ROOT);
            ShopItem found = null;
            for (ShopItem it : database.getAllItems()) {
                if (it.displayName.toLowerCase(Locale.ROOT).contains(needle)
                 || it.minecraftItem.toLowerCase(Locale.ROOT).contains(needle)) {
                    found = it; break;
                }
            }
            if (found == null) { s.sendMessage(PRE + ChatColor.RED + "Item non trouvé : " + args[1]); return true; }
            itemId = found.id;
        }
        boolean upward;
        if (args[2].equalsIgnoreCase("up")) upward = true;
        else if (args[2].equalsIgnoreCase("down")) upward = false;
        else { s.sendMessage(PRE + ChatColor.RED + "up ou down attendu."); return true; }

        double mult;
        try { mult = Double.parseDouble(args[3]); }
        catch (NumberFormatException ex) { s.sendMessage(PRE + ChatColor.RED + "Multiplicateur invalide."); return true; }
        if (mult <= 0 || mult > 10) { s.sendMessage(PRE + ChatColor.RED + "Multiplicateur entre 0.01 et 10."); return true; }

        int dur = parseDur(args, 4);
        double mb = upward ? 1.0 : mult;
        double ms = upward ? mult : 1.0;
        List<Integer> items = new ArrayList<>();
        items.add(itemId);
        long id = manager.launchAubaine(items, upward, mb, ms, dur, true);
        if (id == -1L) s.sendMessage(PRE + ChatColor.RED + "Échec (limite atteinte).");
        else s.sendMessage(PRE + "Aubaine lancée (" + (upward ? "vente↑" : "achat↓") + " ×" + mult + ").");
        return true;
    }

    private boolean cmdStop(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(PRE + ChatColor.RED + "Usage: /shopevent stop <krach|inflation|aubaine|all>"); return true; }
        if (args[1].equalsIgnoreCase("all")) {
            int n = manager.stopAll();
            s.sendMessage(PRE + n + " événement(s) arrêté(s).");
            return true;
        }
        String type = args[1].toUpperCase(Locale.ROOT);
        if (!type.equals(ShopEventManager.TYPE_KRACH)
                && !type.equals(ShopEventManager.TYPE_INFLATION)
                && !type.equals(ShopEventManager.TYPE_AUBAINE)) {
            s.sendMessage(PRE + ChatColor.RED + "Type inconnu. Utilisez : krach, inflation, aubaine ou all.");
            return true;
        }
        int n = manager.stopByType(type);
        if (n == 0) s.sendMessage(PRE + ChatColor.RED + "Aucun événement " + type + " actif.");
        else s.sendMessage(PRE + n + " événement(s) " + type + " arrêté(s).");
        return true;
    }

    private int parseDur(String[] args, int idx) {
        if (args.length <= idx) return -1;
        try { return Math.max(1, Integer.parseInt(args[idx])); }
        catch (NumberFormatException e) { return -1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("shop.admin")) return new ArrayList<>();
        if (args.length == 1) {
            return Arrays.asList("list", "next", "reload", "krach", "inflation", "aubaine", "stop").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("aubaine")) {
            return Arrays.asList("random").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            return Arrays.asList("all", "krach", "inflation", "aubaine").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("aubaine")) {
            return Arrays.asList("up", "down").stream()
                .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
