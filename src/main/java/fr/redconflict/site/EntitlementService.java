package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.boutique.BoutiqueCatalog;
import fr.redconflict.boutique.BoutiqueItem;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Le verrou « tu possèdes déjà ça ».
 *
 * <p>Deux sources, et elles ne disent pas la même chose :
 *
 * <ul>
 *   <li><b>Les permissions du joueur</b> disent ce qu'il <i>peut faire</i>. C'est
 *       la vérité, elle vit dans LuckPerms, et on l'interroge par
 *       {@link Player#hasPermission(String)} — sans dépendre de l'API LuckPerms,
 *       pour que le plugin continue de marcher si le gestionnaire de permissions
 *       change un jour.</li>
 *   <li><b>{@code rc_entitlements}</b> dit ce qu'il a <i>acheté</i>, et jusqu'à
 *       quand. C'est la seule source qui distingue un grade à vie d'un grade de
 *       30 jours, et c'est la seule que le site puisse lire.</li>
 * </ul>
 *
 * <p>Les deux sont réconciliées à chaque connexion : un grade donné à la main par
 * un admin finit donc aussi par bloquer l'achat, et un grade expiré libère le
 * rachat sans intervention.
 *
 * <p><b>Tout passe par un {@link Snapshot}</b>, qui charge les droits du joueur
 * en <i>une seule</i> requête. Ouvrir la boutique interroge une vingtaine
 * d'articles : les tester un par un ferait une vingtaine d'allers-retours
 * réseau sur le thread principal, à chaque ouverture du menu.
 */
public final class EntitlementService {

    /** Ce que le joueur possède déjà d'un article donné. */
    public enum Ownership {
        /** Rien : l'achat est ouvert. */
        NONE,
        /** Acheté temporairement, encore valide. Seul le passage à vie reste utile. */
        OWNED_TEMPORARY,
        /** Acquis à vie. Plus rien à acheter. */
        OWNED_PERMANENT,
        /**
         * Le joueur a déjà tous les nœuds sans les avoir achetés séparément :
         * ils viennent d'un grade supérieur, ou d'un don du staff. L'achat n'a
         * pas d'objet.
         */
        COVERED
    }

    private final RedConflictCore plugin;
    private final SiteDatabase site;
    private final BoutiqueCatalog catalog;

    public EntitlementService(RedConflictCore plugin, SiteDatabase site, BoutiqueCatalog catalog) {
        this.plugin = plugin;
        this.site = site;
        this.catalog = catalog;
    }

    // ── Instantané ─────────────────────────────────────────────────────────────

    /**
     * État de possession du joueur pour tout le catalogue, en une requête.
     *
     * <p>Si la base du site est injoignable, l'instantané se rabat sur les seules
     * permissions : on ne peut plus distinguer temporaire et permanent, alors
     * tout ce que le joueur détient déjà ressort en {@link Ownership#COVERED},
     * qui bloque. Refuser un achat légitime pendant une panne se rattrape ;
     * vendre deux fois le même grade, non.
     *
     * <p>À appeler depuis le thread principal : {@code hasPermission} n'est pas
     * sûr ailleurs.
     */
    public Snapshot snapshot(Player player) {
        Map<String, Row> rows = loadRows(player.getUniqueId());

        // Résolution des permissions une seule fois par nœud : deux grades
        // partagent souvent les mêmes.
        Set<String> held = new HashSet<>();
        Set<String> tested = new HashSet<>();
        for (BoutiqueItem item : catalog.ownable()) {
            for (String node : item.nodes) {
                if (!tested.add(node)) continue;
                if (player.hasPermission(node)) held.add(node);
            }
        }

        return new Snapshot(rows, held);
    }

    /**
     * L'état de possession vu depuis un instant donné.
     *
     * <p>Immuable et sans accès base : une fois construit, on peut l'interroger
     * autant de fois qu'il y a d'articles à dessiner.
     */
    public static final class Snapshot {

        private final Map<String, Row> rows;
        private final Set<String> heldNodes;

        Snapshot(Map<String, Row> rows, Set<String> heldNodes) {
            this.rows = rows;
            this.heldNodes = heldNodes;
        }

        public Ownership ownershipOf(BoutiqueItem item) {
            if (!item.isOwnable()) return Ownership.NONE;

            Row row = rows.get(key(item.category, item.id));

            if (row != null && !row.expired()) {
                // Une ligne posée par la réconciliation dit seulement « il l'a
                // déjà », sans dire d'où ça vient : elle ne peut pas ouvrir un
                // passage à vie sur un droit qu'on n'a pas vendu.
                if (row.fromLuckPerms) return Ownership.COVERED;
                // Sinon la ligne d'achat fait foi, même si les permissions ont
                // été retirées à la main : le joueur a payé.
                return row.permanent ? Ownership.OWNED_PERMANENT : Ownership.OWNED_TEMPORARY;
            }

            return heldNodes.containsAll(item.nodes) ? Ownership.COVERED : Ownership.NONE;
        }

        /**
         * Message de refus, ou {@code null} si l'achat est autorisé.
         *
         * <p>Règle : un droit déjà acquis à vie ne se rachète jamais ; un droit
         * temporaire encore valide ne se re-prend pas en temporaire (LuckPerms
         * refuserait d'empiler le {@code settemp} et le joueur aurait payé pour
         * rien) mais peut être passé à vie.
         */
        public String denialReason(BoutiqueItem item, boolean permanent) {
            switch (ownershipOf(item)) {
                case OWNED_PERMANENT:
                    return "Tu possèdes déjà " + item.name + " à vie.";
                case OWNED_TEMPORARY:
                    if (permanent) return null;   // passage à vie : autorisé
                    Row row = rows.get(key(item.category, item.id));
                    String until = row != null ? row.remainingLabel() : "";
                    return "Tu possèdes déjà " + item.name
                            + (until.isEmpty() ? "." : " (encore " + until + ").")
                            + " Prends-le à vie pour le garder.";
                case COVERED:
                    return "Tu as déjà ces permissions — inutile de les racheter.";
                default:
                    return null;
            }
        }

        public boolean isLocked(BoutiqueItem item, boolean permanent) {
            return denialReason(item, permanent) != null;
        }

        /**
         * Étiquette courte affichée sur la fiche du client : « À vie »,
         * « 12 jours », « Inclus ». Vide si le joueur ne possède rien.
         */
        public String label(BoutiqueItem item) {
            switch (ownershipOf(item)) {
                case OWNED_PERMANENT:
                    return "À vie";
                case OWNED_TEMPORARY:
                    Row row = rows.get(key(item.category, item.id));
                    String remaining = row != null ? row.remainingLabel() : "";
                    return remaining.isEmpty() ? "Possédé" : remaining;
                case COVERED:
                    return "Inclus";
                default:
                    return "";
            }
        }
    }

    // ── Vérification ponctuelle ────────────────────────────────────────────────

    /** Raccourci pour un seul article — construit un instantané au passage. */
    public String denialReason(Player player, BoutiqueItem item, boolean permanent) {
        return snapshot(player).denialReason(item, permanent);
    }

    public Ownership check(Player player, BoutiqueItem item) {
        return snapshot(player).ownershipOf(item);
    }

    // ── Enregistrement ─────────────────────────────────────────────────────────

    /** Enregistre un achat. Écrase la ligne existante : un achat à vie remplace un temporaire. */
    public void grant(OfflinePlayer player, BoutiqueItem item, boolean permanent, String source) {
        if (!item.isOwnable() || !site.isAvailable()) return;

        Timestamp expires = null;
        if (!permanent) {
            long seconds = item.durationSeconds > 0 ? item.durationSeconds : 2592000L;
            expires = new Timestamp(System.currentTimeMillis() + seconds * 1000L);
        }

        String sql = "INSERT INTO rc_entitlements (uuid, category, item_id, expires_at, source) "
                   + "VALUES (?,?,?,?,?) "
                   + "ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at), "
                   + "  source = VALUES(source), granted_at = CURRENT_TIMESTAMP";
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, item.category);
            ps.setString(3, item.id);
            ps.setTimestamp(4, expires);
            ps.setString(5, source);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("grant", e);
        }
    }

    /**
     * Retire un droit accordé.
     *
     * <p>Sert au remboursement d'une commande web : le site inscrit le droit en
     * même temps qu'il débite, dans une seule transaction, pour qu'un second
     * achat du même article soit refusé immédiatement. Si la livraison échoue
     * ensuite, il faut défaire les deux — sans quoi le joueur serait remboursé
     * mais définitivement bloqué à l'achat.
     */
    public void revoke(OfflinePlayer player, String category, String itemId) {
        if (!site.isAvailable()) return;
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM rc_entitlements WHERE uuid = ? AND category = ? AND item_id = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, category);
            ps.setString(3, itemId);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("revoke", e);
        }
    }

    // ── Réconciliation ─────────────────────────────────────────────────────────

    /**
     * Aligne {@code rc_entitlements} sur ce que le joueur peut réellement faire.
     *
     * <p>Deux corrections, dans les deux sens :
     * <ul>
     *   <li>il a les nœuds mais aucune ligne — grade donné à la main, ou reliquat
     *       d'avant ce système : on inscrit une ligne {@code luckperms}, qui
     *       bloque l'achat sans prétendre connaître une date d'expiration ;</li>
     *   <li>il a une ligne mais plus les nœuds — grade expiré ou retiré : on
     *       efface, et l'achat redevient possible.</li>
     * </ul>
     *
     * <p>Les {@code hasPermission} sont résolus ici, sur le thread principal ; le
     * travail en base part ensuite en asynchrone.
     */
    public void reconcile(Player player) {
        if (!site.isAvailable()) return;

        UUID uuid = player.getUniqueId();
        List<BoutiqueItem> ownable = catalog.ownable();

        Set<String> held = new HashSet<>();
        for (BoutiqueItem item : ownable) {
            boolean all = true;
            for (String node : item.nodes) {
                if (!player.hasPermission(node)) { all = false; break; }
            }
            if (all) held.add(key(item.category, item.id));
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> reconcileAsync(uuid, ownable, held));
    }

    private void reconcileAsync(UUID uuid, List<BoutiqueItem> ownable, Set<String> held) {
        Set<String> known = new HashSet<>();
        List<String[]> stale = new ArrayList<>();

        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT category, item_id, expires_at, source FROM rc_entitlements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String category = rs.getString("category");
                    String itemId = rs.getString("item_id");
                    String k = key(category, itemId);
                    known.add(k);
                    Timestamp expires = rs.getTimestamp("expires_at");
                    boolean stillValid = expires == null || expires.getTime() > System.currentTimeMillis();
                    // La ligne ne survit que si le joueur a encore les droits, ou
                    // si son achat court toujours (un retrait manuel n'annule pas
                    // ce qu'il a payé).
                    if (!held.contains(k) && !stillValid) stale.add(new String[] { category, itemId });
                }
            }
        } catch (SQLException e) {
            warn("reconcile/read", e);
            return;
        }

        List<BoutiqueItem> missing = new ArrayList<>();
        for (BoutiqueItem item : ownable) {
            String k = key(item.category, item.id);
            if (held.contains(k) && !known.contains(k)) missing.add(item);
        }

        if (!missing.isEmpty()) {
            String sql = "INSERT IGNORE INTO rc_entitlements (uuid, category, item_id, expires_at, source) "
                       + "VALUES (?,?,?,NULL,'luckperms')";
            try (Connection c = site.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                for (BoutiqueItem item : missing) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, item.category);
                    ps.setString(3, item.id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                warn("reconcile/insert", e);
            }
        }

        if (!stale.isEmpty()) {
            String sql = "DELETE FROM rc_entitlements WHERE uuid = ? AND category = ? AND item_id = ?";
            try (Connection c = site.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                for (String[] row : stale) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, row[0]);
                    ps.setString(3, row[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                warn("reconcile/delete", e);
            }
        }
    }

    /**
     * Efface les droits temporaires arrivés à terme. LuckPerms retire le nœud de
     * son côté au même moment ; cette passe garde seulement la vitrine du site
     * d'accord avec le jeu pour les joueurs qui ne se reconnectent pas.
     */
    public int purgeExpired() {
        if (!site.isAvailable()) return 0;
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM rc_entitlements WHERE expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP")) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            warn("purgeExpired", e);
            return 0;
        }
    }

    // ── Lecture ────────────────────────────────────────────────────────────────

    private Map<String, Row> loadRows(UUID uuid) {
        if (!site.isAvailable()) return Collections.emptyMap();

        Map<String, Row> rows = new HashMap<>();
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT category, item_id, expires_at, source FROM rc_entitlements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(key(rs.getString("category"), rs.getString("item_id")),
                            new Row(rs.getTimestamp("expires_at"), "luckperms".equals(rs.getString("source"))));
                }
            }
        } catch (SQLException e) {
            warn("loadRows", e);
            return Collections.emptyMap();
        }
        return rows;
    }

    private static String key(String category, String itemId) {
        return category + ':' + itemId;
    }

    /** Une ligne de {@code rc_entitlements}, réduite à ce dont le verrou a besoin. */
    private static final class Row {
        final Timestamp expiresAt;
        final boolean permanent;
        final boolean fromLuckPerms;

        Row(Timestamp expiresAt, boolean fromLuckPerms) {
            this.expiresAt = expiresAt;
            this.fromLuckPerms = fromLuckPerms;
            this.permanent = expiresAt == null && !fromLuckPerms;
        }

        boolean expired() {
            return expiresAt != null && expiresAt.getTime() <= System.currentTimeMillis();
        }

        /** « 12 jours », « 3 heures »… pour le message de refus et l'étiquette. */
        String remainingLabel() {
            if (expiresAt == null) return "";
            long ms = expiresAt.getTime() - System.currentTimeMillis();
            if (ms <= 0) return "";
            long days = ms / 86_400_000L;
            if (days >= 1) return days + (days > 1 ? " jours" : " jour");
            long hours = ms / 3_600_000L;
            if (hours >= 1) return hours + (hours > 1 ? " heures" : " heure");
            return Math.max(1L, ms / 60_000L) + " min";
        }
    }

    private void warn(String op, SQLException e) {
        plugin.getLogger().warning("[Entitlements] " + op + " : " + e.getMessage());
    }
}
