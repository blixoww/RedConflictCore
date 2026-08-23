package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.boutique.BoutiqueCatalog;
import fr.redconflict.boutique.BoutiqueItem;
import fr.redconflict.boutique.RewardDispatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Publie {@code boutique/boutique.yml} dans {@code rc_shop_items}.
 *
 * <p>Le site ne connaît pas le fichier YAML et n'a aucun moyen de le lire : il
 * vit dans un conteneur Pterodactyl, pas dans {@code /var/www}. Plutôt que de
 * recopier la grille tarifaire dans l'administration d'Azuriom — deux listes à
 * tenir d'accord, donc deux listes qui divergeront — le serveur de jeu écrit la
 * sienne dans une table que le site se contente d'afficher.
 *
 * <p>Conséquence pratique : <b>on ne modifie jamais la boutique depuis le
 * site</b>. On édite le YAML, on fait {@code /red reload}, et les deux vitrines
 * changent ensemble.
 *
 * <p>L'export est un remplacement complet, dans une transaction : un article
 * retiré du YAML disparaît du site, et personne ne voit jamais un catalogue
 * à moitié réécrit.
 */
public final class CatalogExporter {

    private final RedConflictCore plugin;
    private final SiteDatabase site;
    private final BoutiqueCatalog catalog;

    public CatalogExporter(RedConflictCore plugin, SiteDatabase site, BoutiqueCatalog catalog) {
        this.plugin = plugin;
        this.site = site;
        this.catalog = catalog;
    }

    /** À appeler depuis un thread asynchrone. */
    public void export() {
        if (!site.isAvailable()) return;

        List<BoutiqueItem> items = catalog.all();
        String insert =
                "INSERT INTO rc_shop_items (category, item_id, position, name, icon, description, "
              + "  price_pb, price_pb_perm, duration_s, ownable, needs_online, nodes, visible) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)";

        Connection c = null;
        boolean previousAutoCommit = true;
        try {
            c = site.getConnection();
            previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);

            try (Statement clear = c.createStatement()) {
                // DELETE et non TRUNCATE : TRUNCATE exige le droit DROP, que le
                // compte du plugin n'a volontairement pas, et il valide
                // implicitement la transaction.
                clear.executeUpdate("DELETE FROM rc_shop_items");
            }

            try (PreparedStatement ps = c.prepareStatement(insert)) {
                int position = 0;
                for (BoutiqueItem item : items) {
                    // On exporte les prix *effectifs*, pas les champs bruts du
                    // YAML : un pack déclare un prix_pb_perm que la boutique en
                    // jeu n'applique pas, et le site ne doit pas l'appliquer non
                    // plus. Un seul endroit décide — BoutiqueItem.
                    boolean twoVariants = item.supportsTemporary();

                    // Colonne principale : le prix affiché en premier. Pour un
                    // article à deux versions c'est la location, sinon c'est son
                    // prix unique — et price_pb_perm reste à 0, ce qui dit au
                    // site de n'afficher qu'un seul bouton.
                    int priceMain = twoVariants ? item.pbPriceFor(false) : item.pbPriceFor(true);
                    int pricePerm = twoVariants ? item.pbPriceFor(true) : 0;

                    // Un article sans aucun prix en PB n'a rien à faire sur le
                    // site : la boutique web ne connaît que cette monnaie.
                    if (priceMain <= 0 && pricePerm <= 0) continue;

                    ps.setString(1, item.category);
                    ps.setString(2, item.id);
                    ps.setInt(3, position++);
                    ps.setString(4, truncate(item.name, 191));
                    ps.setString(5, truncate(item.icon, 64));
                    ps.setString(6, item.descriptionAsText());
                    ps.setInt(7, priceMain);
                    ps.setInt(8, pricePerm);
                    ps.setLong(9, twoVariants ? item.durationSeconds : 0L);
                    ps.setBoolean(10, item.isOwnable());
                    // Vrai dès qu'un des deux modes dépose un objet : le site ne
                    // choisit pas encore lequel le joueur prendra.
                    ps.setBoolean(11, RewardDispatcher.requiresOnline(item, true)
                            || RewardDispatcher.requiresOnline(item, false));
                    ps.setString(12, item.nodesAsText());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            c.commit();
            plugin.getLogger().info("[Site] Catalogue publié (" + items.size() + " articles au YAML).");
        } catch (SQLException e) {
            rollbackQuietly(c);
            plugin.getLogger().warning("[Site] Publication du catalogue impossible : " + e.getMessage());
        } finally {
            closeQuietly(c, previousAutoCommit);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void rollbackQuietly(Connection c) {
        if (c == null) return;
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private static void closeQuietly(Connection c, boolean autoCommit) {
        if (c == null) return;
        try { c.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
        try { c.close(); } catch (SQLException ignored) { }
    }
}
