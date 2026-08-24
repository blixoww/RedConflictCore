package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.boutique.BoutiqueItem;
import fr.redconflict.boutique.RewardDispatcher;
import fr.redconflict.core.command.CoreCommand;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /rcdeliver <pseudo> <categorie> <article> <perm|temp> [n° commande]}
 *
 * <p>Le point d'entrée des achats faits sur le site. Azuriom débite les PB,
 * inscrit la commande, puis pousse <b>cette</b> ligne via AzLink — une seule,
 * quelle que soit la complexité de l'article.
 *
 * <p>Pourquoi pas les commandes du YAML directement : un pack donne des items
 * custom ({@code RUBY_SWORD}, {@code COBALT_SWORD}…) qu'Essentials ne connaît
 * pas. Le Core les distribue par l'API Bukkit ; envoyées à la console, ces
 * lignes ne donneraient rien. En passant par ici, le site obtient exactement la
 * même livraison que le comptoir en jeu, sans rien savoir de son contenu.
 *
 * <p>Réservée à la console et aux administrateurs
 * ({@code redconflict.site.deliver}).
 */
public class SiteDeliverCommand extends CoreCommand {

    private final RedConflictCore core;

    public SiteDeliverCommand(RedConflictCore plugin) {
        super(plugin, "rcdeliver", false);
        this.core = plugin;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.GRAY + "Usage : /" + label
                    + " <pseudo> <grade|cmd|kit|spawner|pack> <article> <perm|temp> [n° commande]");
            return;
        }

        String playerName = args[0];
        String category = args[1].toLowerCase();
        String itemId = args[2];
        boolean permanent = !"temp".equalsIgnoreCase(args[3]);

        long orderId = -1L;
        if (args.length >= 5) {
            try {
                orderId = Long.parseLong(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "N° de commande invalide : " + args[4]);
                return;
            }
        }

        OrderService orders = core.getOrderService();
        OrderService.Order order = null;
        if (orderId > 0 && orders != null) {
            order = orders.findPending(orderId);
            if (order == null) {
                // Ni une erreur ni un drame : AzLink a rejoué une commande déjà
                // honorée. On s'arrête avant de livrer une seconde fois.
                sender.sendMessage(ChatColor.YELLOW + "[Site] Commande #" + orderId
                        + " déjà traitée ou inconnue — rien à livrer.");
                return;
            }
            // La commande en base fait foi sur ce qui a été payé : un paramètre
            // de ligne de commande falsifié ne peut pas changer l'article livré.
            category = order.category;
            itemId = order.itemId;
            permanent = order.permanent;
        }

        BoutiqueItem item = core.getBoutiqueCatalog().find(category, itemId);
        if (item == null) {
            fail(sender, orders, orderId, playerName, category, itemId,
                    order != null ? order.pricePb : 0,
                    "Article inconnu : " + category + "/" + itemId);
            return;
        }

        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null && RewardDispatcher.requiresOnline(item, permanent)) {
            // Le site pose need_online sur ce genre d'article ; si on arrive
            // quand même ici, mieux vaut rembourser que déposer dans le vide.
            fail(sender, orders, orderId, playerName, category, itemId,
                    order != null ? order.pricePb : 0,
                    playerName + " doit être connecté pour recevoir " + item.name + ".");
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(playerName);

        core.getRewardDispatcher().execute(playerName, target.getUniqueId(), item, permanent);

        EntitlementService entitlements = core.getEntitlementService();
        if (entitlements != null) {
            entitlements.grant(target, item, permanent, "site");
        }

        if (orderId > 0 && orders != null && !orders.markDelivered(orderId)) {
            // La ligne a changé d'état pendant qu'on livrait : on l'écrit dans
            // les logs, l'article est déjà donné et on ne peut plus le reprendre.
            core.getLogger().warning("[Site] Commande #" + orderId + " livrée à " + playerName
                    + " mais son statut avait déjà changé — à vérifier.");
        }

        fr.redconflict.boutique.BoutiqueAnnonce.annoncer(core, playerName, item.name, permanent);

        if (online != null) {
            online.sendMessage(ChatColor.GREEN + "[Boutique] " + ChatColor.WHITE + item.name
                    + ChatColor.GRAY + " acheté sur le site vient de t'être remis.");
        }
        sender.sendMessage(ChatColor.GREEN + "[Site] " + item.name + " livré à " + playerName + ".");
        core.getLogger().info("[Site] Livraison " + category + "/" + itemId
                + (permanent ? " (à vie)" : " (temporaire)") + " → " + playerName
                + (orderId > 0 ? " [commande #" + orderId + "]" : ""));
    }

    /**
     * Refuse la livraison, rembourse si l'achat était payé, et laisse une trace
     * lisible côté site.
     */
    private void fail(CommandSender sender, OrderService orders, long orderId,
                      String playerName, String category, String itemId,
                      int pricePb, String reason) {
        sender.sendMessage(ChatColor.RED + "[Site] " + reason);
        core.getLogger().warning("[Site] Livraison refusée pour " + playerName + " : " + reason);

        if (orderId <= 0 || orders == null) return;

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        // Le site a inscrit le droit en même temps qu'il débitait, pour bloquer
        // un second achat. La livraison ayant échoué, il faut le reprendre :
        // sinon le joueur est remboursé mais ne peut plus jamais racheter.
        EntitlementService entitlements = core.getEntitlementService();
        if (entitlements != null && category != null && itemId != null) {
            entitlements.revoke(target, category, itemId);
        }

        if (pricePb > 0 && core.getPBManager() != null) {
            core.getPBManager().add(target, pricePb, "REFUND:order#" + orderId, "site");
            orders.markRefunded(orderId, reason);
        } else {
            orders.markFailed(orderId, reason);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command,
                                      String alias, String[] args) {
        // Commande de machine : la complétion n'aide personne, et proposer la
        // liste des articles à un joueur curieux n'a aucun intérêt.
        return new ArrayList<>();
    }
}
