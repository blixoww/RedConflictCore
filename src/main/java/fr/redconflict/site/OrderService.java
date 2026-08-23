package fr.redconflict.site;

import fr.redconflict.RedConflictCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Les commandes passées sur le site, vues depuis le serveur de jeu.
 *
 * <p>Le site débite les PB et crée la ligne dans une seule transaction, puis
 * demande la livraison. Ici on ne fait que refermer la boucle : marquer livré,
 * ou rembourser et dire pourquoi.
 *
 * <p><b>Une ligne ne se livre qu'une fois.</b> Le passage à {@code delivered}
 * n'a lieu que si la ligne est encore {@code pending} : si AzLink rejoue une
 * commande — ça arrive après un redémarrage mal tombé — la seconde tentative ne
 * trouve plus rien à faire et n'accorde pas le grade une deuxième fois.
 */
public final class OrderService {

    /** Une commande web en attente, réduite à ce qu'il faut pour la livrer. */
    public static final class Order {
        public final long id;
        public final String category;
        public final String itemId;
        public final boolean permanent;
        public final int pricePb;

        Order(long id, String category, String itemId, boolean permanent, int pricePb) {
            this.id = id;
            this.category = category;
            this.itemId = itemId;
            this.permanent = permanent;
            this.pricePb = pricePb;
        }
    }

    private final RedConflictCore plugin;
    private final SiteDatabase site;

    public OrderService(RedConflictCore plugin, SiteDatabase site) {
        this.plugin = plugin;
        this.site = site;
    }

    /** {@code null} si la commande n'existe pas ou n'est plus en attente. */
    public Order findPending(long id) {
        if (!site.isAvailable()) return null;
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT category, item_id, permanent, price_pb FROM rc_orders "
                   + "WHERE id = ? AND status = 'pending'")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Order(id, rs.getString("category"), rs.getString("item_id"),
                        rs.getBoolean("permanent"), rs.getInt("price_pb"));
            }
        } catch (SQLException e) {
            warn("findPending", e);
            return null;
        }
    }

    /**
     * Marque la commande livrée.
     *
     * @return {@code false} si la ligne n'était plus en attente — donc si une
     *         autre exécution l'a déjà livrée. L'appelant doit alors ne rien
     *         donner : c'est le garde-fou contre la double livraison.
     */
    public boolean markDelivered(long id) {
        return transition(id, "delivered", "");
    }

    /** Marque la commande remboursée et note la raison, visible par le joueur sur le site. */
    public boolean markRefunded(long id, String reason) {
        return transition(id, "refunded", reason);
    }

    /** Échec sans remboursement automatique — au staff de trancher. */
    public boolean markFailed(long id, String reason) {
        return transition(id, "failed", reason);
    }

    private boolean transition(long id, String status, String reason) {
        if (!site.isAvailable()) return false;
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE rc_orders SET status = ?, error = ?, "
                   + "  delivered_at = CASE WHEN ? = 'delivered' THEN CURRENT_TIMESTAMP ELSE delivered_at END "
                   + "WHERE id = ? AND status = 'pending'")) {
            ps.setString(1, status);
            ps.setString(2, truncate(reason, 191));
            ps.setString(3, status);
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("transition", e);
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void warn(String op, SQLException e) {
        plugin.getLogger().warning("[Orders] " + op + " : " + e.getMessage());
    }
}
