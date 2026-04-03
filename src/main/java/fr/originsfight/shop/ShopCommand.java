package fr.originsfight.shop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final ShopManager manager;

    public ShopCommand(ShopManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = label.toLowerCase(Locale.ROOT);

        // ── /shopdebug ─────────────────────────────────────────────────────
        if (cmdName.equals("shopdebug")) {
            if (!sender.hasPermission("shop.admin")) {
                sender.sendMessage("§cVous n'avez pas la permission.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("§cUsage: /shopdebug <tick all|info|reset>");
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "tick": {
                    if (args.length < 2 || !args[1].equalsIgnoreCase("all")) {
                        sender.sendMessage("§cUsage: /shopdebug tick all");
                        return true;
                    }
                    sender.sendMessage("§6[Shop] §eDébut de la simulation de 24h (async)...");
                    manager.simulateDailyRegression(() -> {
                        sender.sendMessage("§6[Shop] §aSimulation terminée ! Régression appliquée.");
                        sender.sendMessage("§7Les prix ont été rapprochés de leur valeur de base.");
                        sender.sendMessage("§7L'historique des prix a été mis à jour.");
                    });
                    return true;
                }
                case "info": {
                    String[] summary = manager.getDatabase().getMarketSummary();
                    for (String line : summary) {
                        sender.sendMessage(line);
                    }
                    long nextTick = manager.getNextRegressionTime();
                    if (nextTick > 0) {
                        long remaining = (nextTick - System.currentTimeMillis()) / 1000L;
                        long hours = remaining / 3600;
                        long minutes = (remaining % 3600) / 60;
                        sender.sendMessage("§7Prochaine régression : §f" + hours + "h " + minutes + "min");
                    } else {
                        sender.sendMessage("§7Prochaine régression : §finconnue");
                    }
                    return true;
                }
                case "reset": {
                    sender.sendMessage("§6[Shop] §eRéinitialisation du catalogue...");
                    manager.resetAndReloadShop();
                    sender.sendMessage("§6[Shop] §aCatalogue rechargé avec succès !");
                    return true;
                }
                default:
                    sender.sendMessage("§cUsage: /shopdebug <tick all|info|reset>");
                    return true;
            }
        }

        // ── /shop ──────────────────────────────────────────────────────────
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cCette commande est réservée aux joueurs.");
            return true;
        }

        Player player = (Player) sender;

        // /shop (pas d'args) ou /shop open => ouvrir le GUI
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("open"))) {
            manager.openShop(player);
            manager.sendCategories(player);
            return true;
        }

        // /shop help
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§6[Shop] §eCommandes Shop");
            player.sendMessage("§7  /shop §f- Ouvrir le Shop");
            player.sendMessage("§7  /shop help §f- Afficher cette aide");
            player.sendMessage("§7  /shop next §f- Prochain rééquilibrage des prix");
            if (player.hasPermission("shop.admin")) {
                player.sendMessage("§3  — Staff —");
                player.sendMessage("§7  /shopdebug info §f- Résumé du marché");
                player.sendMessage("§7  /shopdebug tick all §f- Simuler une régression 24h");
                player.sendMessage("§7  /shopdebug reset §f- Réinitialiser le catalogue");
            }
            return true;
        }

        // /shop next => prochain rééquilibrage
        if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            long nextTick = manager.getNextRegressionTime();
            if (nextTick <= 0) {
                player.sendMessage("§6[Shop] §cInformation indisponible.");
                return true;
            }
            long remaining = (nextTick - System.currentTimeMillis()) / 1000L;
            if (remaining < 0) remaining = 0;
            long hours   = remaining / 3600;
            long minutes = (remaining % 3600) / 60;
            long seconds = remaining % 60;

            player.sendMessage("§6[Shop] §eProchain rééquilibrage des prix :");
            player.sendMessage("§7  → dans §f" + hours + "h " + minutes + "min " + seconds + "s");
            player.sendMessage("§8  (Les prix sont recalculés toutes les 24h en fonction de l'offre et de la demande des 7 derniers jours)");
            return true;
        }

        player.sendMessage("§cSous-commande inconnue. Tapez §e/shop help §cpour la liste des commandes.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String cmdName = alias.toLowerCase(Locale.ROOT);

        if (cmdName.equals("shopdebug")) {
            if (!sender.hasPermission("shop.admin")) return new ArrayList<>();
            if (args.length == 1) {
                return Arrays.asList("tick", "info", "reset").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("tick")) {
                return Arrays.asList("all").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            }
            return new ArrayList<>();
        }

        // /shop
        if (args.length == 1) {
            return Arrays.asList("open", "next").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
