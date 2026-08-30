package fr.redconflict.shop;

import fr.redconflict.core.command.CoreCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ShopCommand extends CoreCommand {

    private final ShopManager manager;

    public ShopCommand(JavaPlugin plugin, ShopManager manager) {
        // « bourse » : le nom sert aux messages et au journal ; il doit dire ce
        // que la commande ouvre, pas ce qu'elle s'appelait avant.
        super(plugin, "bourse", false);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        String cmdName = label.toLowerCase(Locale.ROOT);

        // ── /shopdebug ─────────────────────────────────────────────────────
        if (cmdName.equals("shopdebug")) {
            if (!sender.hasPermission("shop.admin")) {
                sender.sendMessage("§cVous n'avez pas la permission.");
                return;
            }
            if (args.length == 0) {
                sender.sendMessage("§cUsage: /shopdebug <tick all|info|reset>");
                return;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "tick": {
                    if (args.length < 2 || !args[1].equalsIgnoreCase("all")) {
                        sender.sendMessage("§cUsage: /shopdebug tick all");
                        return;
                    }
                    sender.sendMessage("§6[Shop] §eDébut de la simulation de 24h (async)...");
                    manager.simulateDailyRegression(() -> {
                        sender.sendMessage("§6[Shop] §aSimulation terminée ! Régression appliquée.");
                        sender.sendMessage("§7Les prix ont été rapprochés de leur valeur de base.");
                        sender.sendMessage("§7L'historique des prix a été mis à jour.");
                    });
                    return;
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
                    return;
                }
                case "reset": {
                    sender.sendMessage("§6[Shop] §eRéinitialisation du catalogue...");
                    manager.resetAndReloadShop();
                    sender.sendMessage("§6[Shop] §aCatalogue rechargé avec succès !");
                    return;
                }
                default:
                    sender.sendMessage("§cUsage: /shopdebug <tick all|info|reset>");
                    return;
            }
        }

        // ── /shop ──────────────────────────────────────────────────────────
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cCette commande est réservée aux joueurs.");
            return;
        }

        Player player = (Player) sender;

        // /shop (sans args) => ouvrir le GUI
        if (args.length == 0) {
            manager.openShop(player);
            manager.sendCategories(player);
            return;
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
            return;
        }

        // /shop next => prochain rééquilibrage
        if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            long nextTick = manager.getNextRegressionTime();
            if (nextTick <= 0) {
                player.sendMessage("§6[Shop] §cInformation indisponible.");
                return;
            }
            long remaining = (nextTick - System.currentTimeMillis()) / 1000L;
            if (remaining < 0) remaining = 0;
            long hours   = remaining / 3600;
            long minutes = (remaining % 3600) / 60;
            long seconds = remaining % 60;

            player.sendMessage("§6[Shop] §eProchain rééquilibrage des prix :");
            player.sendMessage("§7  → dans §f" + hours + "h " + minutes + "min " + seconds + "s");
            player.sendMessage("§8  (Les prix sont recalculés toutes les 24h en fonction de l'offre et de la demande des 7 derniers jours)");
            return;
        }

        player.sendMessage("§cSous-commande inconnue. Tapez §e/shop help §cpour la liste des commandes.");
        return;
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
            return Arrays.asList("next").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
