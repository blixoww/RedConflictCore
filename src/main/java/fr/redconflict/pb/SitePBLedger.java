package fr.redconflict.pb;

import fr.redconflict.RedConflictCore;
import fr.redconflict.site.SiteDatabase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Ledger partagé : le solde PB <b>est</b> {@code users.money}, la bourse native
 * d'Azuriom.
 *
 * <p>Il n'y a pas de copie côté jeu, et c'est le point important. Un solde
 * dépensable au comptoir comme sur le site ne peut pas être répliqué : deux
 * exemplaires et deux débiteurs, c'est un joueur qui dépense deux fois les mêmes
 * PB pendant l'intervalle de synchronisation. On écrit donc la même ligne que le
 * site, chacun sous {@code SELECT ... FOR UPDATE} — celui qui arrive second
 * attend, relit le solde déjà décrémenté, et se voit refuser l'achat s'il ne
 * reste pas assez.
 *
 * <p>Se greffer sur {@code users.money} plutôt que sur une table à nous donne en
 * prime tout ce qu'Azuriom sait déjà faire d'une bourse : passerelles de
 * paiement, historique, cartes cadeaux, crédit manuel depuis l'administration.
 * Échelle : 1 PB = 1 unité de {@code money}.
 *
 * <p><b>La jointure passe par {@code users.game_id}</b>, qui porte l'UUID sans
 * tirets. Le compte SQL n'a de droits que sur quatre colonnes de {@code users},
 * dont une seule en écriture : il ne peut pas lire un mot de passe.
 *
 * <p><b>Toutes les méthodes touchent le réseau.</b> À n'appeler que depuis un
 * thread asynchrone, ou depuis une action ponctuelle dont on accepte qu'elle
 * bloque quelques millisecondes. {@link PBManager} s'en charge.
 */
public final class SitePBLedger implements PBLedger {

    private final RedConflictCore plugin;
    private final SiteDatabase site;

    public SitePBLedger(RedConflictCore plugin, SiteDatabase site) {
        this.plugin = plugin;
        this.site = site;
    }

    @Override public String getName() { return "site (users.money)"; }

    @Override public boolean isAvailable() { return site.isAvailable(); }

    /**
     * Sans objet ici : la ligne du joueur, c'est son compte Azuriom, et il en a
     * forcément un — le launcher s'authentifie via AzAuth, on ne peut pas jouer
     * sans. Le plugin n'a d'ailleurs pas le droit d'insérer dans {@code users}.
     */
    @Override
    public void ensure(UUID uuid, String name) {
        // Rien à faire.
    }

    @Override
    public int get(UUID uuid) {
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT money FROM users WHERE game_id = ?")) {
            ps.setString(1, gameId(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? toPB(rs.getBigDecimal(1)) : 0;
            }
        } catch (SQLException e) {
            warn("get", e);
            return 0;
        }
    }

    @Override
    public boolean add(UUID uuid, int amount) {
        if (amount <= 0) return false;
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET money = money + ? WHERE game_id = ?")) {
            ps.setInt(1, amount);
            ps.setString(2, gameId(uuid));
            int updated = ps.executeUpdate();
            if (updated == 0) noAccount(uuid, "crédit de " + amount + " PB");
            return updated > 0;
        } catch (SQLException e) {
            warn("add", e);
            return false;
        }
    }

    /**
     * Débit atomique.
     *
     * <p>Le {@code FOR UPDATE} pose un verrou de ligne InnoDB tenu jusqu'au
     * commit : tout autre débit du même joueur — y compris celui de la boutique
     * web, qui exécute la même séquence en PHP — attend derrière. Sans lui, deux
     * achats simultanés liraient tous deux l'ancien solde et passeraient tous
     * deux, laissant le compte à découvert.
     */
    @Override
    public boolean remove(UUID uuid, int amount) {
        if (amount <= 0) return false;
        Connection c = null;
        boolean previousAutoCommit = true;
        try {
            c = site.getConnection();
            previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);

            BigDecimal current = null;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT money FROM users WHERE game_id = ? FOR UPDATE")) {
                sel.setString(1, gameId(uuid));
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) current = rs.getBigDecimal(1);
                }
            }
            if (current == null) {
                c.rollback();
                noAccount(uuid, "débit de " + amount + " PB");
                return false;
            }
            if (toPB(current) < amount) {
                c.rollback();
                return false;
            }

            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE users SET money = money - ? WHERE game_id = ?")) {
                upd.setInt(1, amount);
                upd.setString(2, gameId(uuid));
                upd.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(c);
            warn("remove", e);
            return false;
        } finally {
            closeQuietly(c, previousAutoCommit);
        }
    }

    @Override
    public void set(UUID uuid, int amount) {
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET money = ? WHERE game_id = ?")) {
            ps.setInt(1, Math.max(0, amount));
            ps.setString(2, gameId(uuid));
            if (ps.executeUpdate() == 0) noAccount(uuid, "solde fixé à " + amount + " PB");
        } catch (SQLException e) {
            warn("set", e);
        }
    }

    // ── Journal partagé ────────────────────────────────────────────────────────

    /**
     * Écrit le mouvement dans {@code rc_pb_log}, que le site lit pour
     * l'historique du joueur. Double le journal fichier de {@link PBLogger},
     * volontairement : le fichier reste lisible quand la base est en panne, la
     * base reste lisible quand on n'a pas d'accès SSH.
     */
    public void journal(UUID uuid, String name, int delta, int balance, String source, String reason) {
        String sql = "INSERT INTO rc_pb_log (uuid, name, delta, balance, source, reason) "
                   + "VALUES (?,?,?,?,?,?)";
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name != null ? name : "");
            ps.setInt(3, delta);
            ps.setInt(4, balance);
            ps.setString(5, source);
            ps.setString(6, truncate(reason, 191));
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("journal", e);
        }
    }

    // ── Utilitaires ────────────────────────────────────────────────────────────

    /** Azuriom stocke l'UUID sans tirets dans {@code users.game_id}. */
    public static String gameId(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /**
     * {@code money} est un {@code DECIMAL(14,2)} : on tronque vers le bas.
     * Arrondir au supérieur offrirait un PB gratuit à qui aurait 9,99 en banque.
     */
    private static int toPB(BigDecimal money) {
        if (money == null) return 0;
        return money.setScale(0, RoundingMode.FLOOR).intValue();
    }

    /**
     * Un joueur en jeu sans compte Azuriom ne devrait pas exister — AzAuth est
     * le seul chemin de connexion. Si ça arrive, c'est un symptôme (compte
     * supprimé, {@code game_id} vide, mauvais mode de jeu), pas une broutille :
     * on le dit fort plutôt que de perdre silencieusement des PB.
     */
    private void noAccount(UUID uuid, String operation) {
        plugin.getLogger().warning("[PB/site] Aucun compte Azuriom pour game_id="
                + gameId(uuid) + " — " + operation + " ignoré. "
                + "Vérifie AZURIOM_GAME=mc-offline et la colonne users.game_id.");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void rollbackQuietly(Connection c) {
        if (c == null) return;
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private void closeQuietly(Connection c, boolean autoCommit) {
        if (c == null) return;
        try { c.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
        try { c.close(); } catch (SQLException ignored) { }
    }

    private void warn(String op, SQLException e) {
        plugin.getLogger().warning("[PB/site] " + op + " : " + e.getMessage());
    }
}
