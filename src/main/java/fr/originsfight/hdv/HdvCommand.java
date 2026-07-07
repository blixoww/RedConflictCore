package fr.originsfight.hdv;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import fr.originsfight.core.command.CoreCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class HdvCommand extends CoreCommand {
    private static final String PRE = ChatColor.GOLD + "[HDV] " + ChatColor.RESET;

    private static final String NOPERM = ChatColor.RED + "Vous n'avez pas la permission.";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private final HdvManager manager;

    public HdvCommand(JavaPlugin plugin, HdvManager manager) {
        super(plugin, "hdv", false);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender s, String label, String[] args) {
        if (args.length == 0) {
            // Ouvre directement l'HDV si c'est un joueur, sinon affiche l'aide.
            if (s instanceof Player) {
                this.manager.sendOpen((Player) s);
            } else {
                help(s);
            }
            return;
        }
        switch (args[0].toLowerCase()) {
            case "help":
                help(s);
                return;
            case "info":
                info(s);
                return;
            case "collect":
                collect(s);
                return;
            case "history":
                history(s, args);
                return;
            case "list":
                list(s, args);
                return;
            case "clear":
                clear(s, args);
                return;
            case "reload":
                reload(s);
                return;
            case "expire":
                expire(s, args);
                return;
        }
        s.sendMessage(PRE + ChatColor.RED + "Sous-commande inconnue. Tapez " + ChatColor.YELLOW + "/hdv help" + ChatColor.RED + " pour la liste des commandes.");
    }

    private void help(CommandSender s) {
        s.sendMessage(PRE + ChatColor.YELLOW + "Commandes HDV");
        line(s, "/hdv", "Ouvrir l'Hôtel des Ventes");
        line(s, "/hdv info", "Voir votre solde, annonces et gains");
        line(s, "/hdv collect", "Collecter vos gains en attente");
        line(s, "/hdv history [page]", "Voir l'historique de vos transactions");
        if (s.isOp()) {
            s.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "  — Staff —");
            line(s, "/hdv list [page]", "Lister toutes les annonces actives");
            line(s, "/hdv clear <joueur>", "Supprimer les annonces d'un joueur");
            line(s, "/hdv history <joueur> [page]", "Historique d'un joueur");
            line(s, "/hdv expire <id>", "Forcer l'expiration d'une annonce");
        }
    }

    private boolean expire(CommandSender s, String[] args) {
        if (!s.isOp()) {
            s.sendMessage(NOPERM);
            return true;
        }
        if (args.length < 2) {
            s.sendMessage(PRE + ChatColor.RED + "Usage : /hdv expire <id_annonce>");
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            s.sendMessage(PRE + ChatColor.RED + "ID invalide : " + args[1]);
            return true;
        }
        boolean ok = this.manager.getDatabase().forceExpireListing(id);
        if (ok) {
            s.sendMessage(PRE + ChatColor.GREEN + "Annonce #" + id + " expirée avec succès. Le vendeur peut la récupérer.");
        } else {
            s.sendMessage(PRE + ChatColor.RED + "Annonce #" + id + " introuvable ou déjà vendue/annulée.");
        }
        return true;
    }

    private void line(CommandSender s, String cmd, String desc) {
        s.sendMessage(ChatColor.GRAY + "  " + cmd + ChatColor.DARK_GRAY + " " + ChatColor.WHITE + desc);
    }

    private boolean info(CommandSender s) {
        if (!(s instanceof Player)) {
            s.sendMessage("Commande en jeu uniquement.");
            return true;
        }
        Player p = (Player)s;
        long bal = (this.manager.getEconomyProvider() != null) ? this.manager.getEconomyProvider().getBalance(p) : 0L;
        long gains = this.manager.getDatabase().getPendingEarnings(p.getUniqueId().toString());
        int active = this.manager.getDatabase().countActiveListings(p.getUniqueId().toString());
        s.sendMessage(PRE + ChatColor.YELLOW + "Votre compte HDV");
        s.sendMessage(PRE + ChatColor.YELLOW + "Solde        : " + ChatColor.GOLD + fmt(bal) + " $");
        s.sendMessage(PRE + ChatColor.YELLOW + "Gains att.   : " + ChatColor.GREEN + fmt(gains) + " $" + ((gains > 0L) ? (ChatColor.GRAY + " /hdv collect") : ""));
        s.sendMessage(PRE + ChatColor.YELLOW + "Annonces     : " + ChatColor.WHITE + active + ChatColor.GRAY + "/" + HdvDatabase.MAX_LISTINGS_PER_PLAYER);
        return true;
    }

    private boolean collect(CommandSender s) {
        if (!(s instanceof Player)) {
            s.sendMessage("Commande en jeu uniquement.");
            return true;
        }
        Player p = (Player)s;
        long pending = this.manager.getDatabase().getPendingEarnings(p.getUniqueId().toString());
        if (pending <= 0L) {
            s.sendMessage(PRE + ChatColor.GRAY + "Vous n'avez aucun gain en attente.");
            s.sendMessage(ChatColor.DARK_GRAY + "  -> Vendez des items depuis l'HDV pour accumuler des gains.");
            return true;
        }
        // Afficher un apercu avant de collecter
        s.sendMessage(PRE + ChatColor.YELLOW + "Collecte de " + ChatColor.GOLD + fmt(pending) + " $" + ChatColor.YELLOW + " en cours...");
        Bukkit.getScheduler().runTask(
                plugin, () -> this.manager.handleCollect(p));
        return true;
    }

    private boolean history(CommandSender s, String[] args) {
        String target = null;
        int page = 1;
        if (args.length >= 2)
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                if (!s.isOp()) {
                    s.sendMessage(NOPERM);
                    return true;
                }
                target = args[1];
                if (args.length >= 3)
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        page = 1;
                    }
            }
        if (!(s instanceof Player) && target == null) {
            s.sendMessage("Pour un joueur : /hdv history <joueur> [page]");
            return true;
        }
        String filterName = target != null ? target : s.getName();
        int pageF = Math.max(1, page);
        List<String[]> rows = this.manager.getDatabase().getTransactions(filterName, pageF * 10);
        int start = (pageF - 1) * 10;
        if (start >= rows.size() && !rows.isEmpty())
            start = Math.max(0, rows.size() - 10);
        List<String[]> page_rows = rows.subList(
                Math.min(start, rows.size()),
                Math.min(start + 10, rows.size()));

        // En-tete
        // Rajouter un système de "proportionnalité" pour les barres de séparation
        s.sendMessage("");
        s.sendMessage(ChatColor.DARK_GRAY + "§m-----------------------");
        s.sendMessage(ChatColor.GOLD + "  HDV " + ChatColor.DARK_GRAY + "| "
                + ChatColor.WHITE + "Historique de " + ChatColor.AQUA + filterName
                + ChatColor.DARK_GRAY + "  [Page " + pageF + "]");
        s.sendMessage(ChatColor.DARK_GRAY + "§m-----------------------");

        if (page_rows.isEmpty()) {
            s.sendMessage(ChatColor.GRAY + "  Aucune transaction trouvee sur cette page.");
            s.sendMessage(ChatColor.DARK_GRAY + "  Utilisez /hdv history pour voir la premiere page.");
        } else {
            int num = start + 1;
            for (String[] r : page_rows) {
                long ts       = Long.parseLong(r[0]);
                String buyer  = r[1];
                String seller = r[2];
                String item   = r[3];
                int    qty    = Integer.parseInt(r[4]);
                long   price  = Long.parseLong(r[5]);
                long   unitP  = qty > 0 ? price / qty : price;
                String date   = DF.format(java.time.Instant.ofEpochSecond(ts));

                boolean isBuyer  = buyer.equalsIgnoreCase(filterName);
                boolean isSeller = seller.equalsIgnoreCase(filterName);

                // Role et couleur
                String roleTag;
                String priceStr;
                if (isBuyer && !isSeller) {
                    roleTag   = ChatColor.RED + "" + ChatColor.BOLD + "ACHAT  ";
                    priceStr  = ChatColor.RED + "-" + fmt(price) + " $";
                } else if (isSeller && !isBuyer) {
                    roleTag   = ChatColor.GREEN + "" + ChatColor.BOLD + "VENTE  ";
                    priceStr  = ChatColor.GREEN + "+" + fmt(price) + " $";
                } else {
                    roleTag   = ChatColor.YELLOW + "" + ChatColor.BOLD + "ECHANGE";
                    priceStr  = ChatColor.GOLD + fmt(price) + " $";
                }

                // Ligne principale
                s.sendMessage(
                    ChatColor.DARK_GRAY + "" + num + ". "
                    + roleTag + ChatColor.RESET
                    + ChatColor.DARK_GRAY + " [" + ChatColor.GRAY + date + ChatColor.DARK_GRAY + "]  "
                    + ChatColor.WHITE + "" + ChatColor.BOLD + qty + "x "
                    + ChatColor.YELLOW + item
                    + ChatColor.DARK_GRAY + "  ->  "
                    + priceStr
                );
                // Ligne detail
                s.sendMessage(
                    ChatColor.DARK_GRAY + "        "
                    + ChatColor.GRAY + "Acheteur : " + ChatColor.AQUA + buyer
                    + ChatColor.DARK_GRAY + "  |  "
                    + ChatColor.GRAY + "Vendeur : " + ChatColor.AQUA + seller
                    + ChatColor.DARK_GRAY + "  |  "
                    + ChatColor.GRAY + "Prix/u : " + ChatColor.GOLD + fmt(unitP) + " $"
                );
                num++;
            }
        }

        // Pied de page + pagination
        s.sendMessage(ChatColor.DARK_GRAY + "§m-----------------------");
        String baseCmd = "/hdv history" + (target != null && s.isOp() ? " " + target : "");
        StringBuilder nav = new StringBuilder(ChatColor.GRAY + "  ");
        if (pageF > 1)
            nav.append(ChatColor.AQUA).append(baseCmd).append(" ").append(pageF - 1)
               .append(ChatColor.GRAY).append(" << Precedent");
        if (pageF > 1 && rows.size() > start + 10)
            nav.append(ChatColor.DARK_GRAY).append("  |  ");
        if (rows.size() > start + 10)
            nav.append(ChatColor.AQUA).append(baseCmd).append(" ").append(pageF + 1)
               .append(ChatColor.GRAY).append(" Suivant >>");
        if (nav.toString().trim().length() > 2) s.sendMessage(nav.toString());
        s.sendMessage("");
        return true;
    }

    private boolean list(CommandSender s, String[] args) {
        if (!s.isOp()) {
            s.sendMessage(NOPERM);
            return true;
        }
        int page = 0;
        if (args.length >= 2)
            try {
                page = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException numberFormatException) {}
        List<HdvListing> listings = this.manager.getDatabase().getActiveListings(page, 10, "");
        if (listings.isEmpty()) {
            s.sendMessage(PRE + "Aucune annonce active (page " + (page + 1) + ").");
            return true;
        }
        s.sendMessage(PRE + ChatColor.YELLOW + "Annonces actives (page " + (page + 1) + ")" );
        for (HdvListing l : listings) {
            String name = (l.getItem() != null) ? ((l.getItem().hasItemMeta() && l.getItem().getItemMeta().hasDisplayName()) ? l.getItem().getItemMeta().getDisplayName() : l.getItem().getType().name()) : "?";
            s.sendMessage(ChatColor.GRAY + "  #" + l.getId() + " " + ChatColor.WHITE + l
                    .getQuantity() + "x " + name + ChatColor.GRAY + " par " + ChatColor.AQUA + l
                    .getSellerName() + ChatColor.GRAY + " @ " + ChatColor.GOLD +
                    fmt(l.getPricePerUnit()) + "/u" + ChatColor.DARK_GRAY + " [id:" + l
                    .getId() + "]");
        }
        return true;
    }

    private boolean clear(CommandSender s, String[] args) {
        if (!s.isOp()) {
            s.sendMessage(NOPERM);
            return true;
        }
        if (args.length < 2) {
            s.sendMessage(PRE + ChatColor.RED + "Usage : /hdv clear <joueur>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            s.sendMessage(PRE + ChatColor.RED + "Joueur introuvable ou hors-ligne.");
            return true;
        }
        int removed = this.manager.getDatabase().clearListingsForPlayer(target.getUniqueId().toString());
        s.sendMessage(PRE + ChatColor.GREEN + removed + " annonce(s) supprimpour " + target.getName() + ".");
        return true;
    }

    private boolean reload(CommandSender s) {
        if (!s.isOp()) {
            s.sendMessage(NOPERM);
            return true;
        }
        s.sendMessage(PRE + ChatColor.GREEN + "La base de donnees HDV est persistante (SQLite), pas de rechargement necessaire.");
        s.sendMessage(PRE + ChatColor.GRAY + "Pour vider les annonces expirees, redemarrez le serveur.");
        return true;
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = s.isOp()
                ? Arrays.asList("help", "info", "collect", "history", "list", "clear", "reload", "expire")
                : Arrays.asList("help", "info", "collect", "history");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("expire") && s.isOp()) {
            List<Integer> ids = this.manager.getDatabase().getActiveListingIds();
            String prefix = args[1];
            List<String> result = new ArrayList<>();
            for (Integer id : ids) {
                String sid = String.valueOf(id);
                if (sid.startsWith(prefix)) result.add(sid);
            }
            return result;
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        List<String> r = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase()))
                r.add(s);
        }
        return r;
    }

    private String fmt(long v) {
        if (v >= 1000000L)
            return String.format("%.1fM", v / 1000000.0D);
        if (v >= 1000L)
            return String.format("%.1fK", v / 1000.0D);
        return String.valueOf(v);
    }
}
