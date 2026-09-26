package fr.redconflict.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Corrections de données à passer <b>une seule fois</b> sur la base H2.
 *
 * <p><b>Pourquoi ce besoin.</b> Plusieurs catalogues (bourse, succès) ne sont
 * lus depuis le YAML qu'à la première installation : ensuite la base fait foi.
 * Corriger un prix ou un compteur faussé dans le code ne suffit donc pas, il
 * faut réécrire les lignes déjà en place — et ne le faire qu'une fois, sinon
 * chaque redémarrage réappliquerait la correction.
 *
 * <p><b>Faction et Minage partagent la base.</b> Le travail et la pose du
 * marqueur tiennent dans une même transaction : si les deux serveurs démarrent
 * ensemble, le second bute sur la clé primaire du marqueur, sa transaction est
 * annulée, et la correction n'est appliquée qu'une fois.
 */
public final class Migrations {

    private static final Logger LOG = Logger.getLogger("Migrations");

    /** Le travail d'une migration, sur la connexion (transactionnelle) fournie. */
    public interface Work {
        /** @return un court résumé pour les logs (« 12 lignes corrigées »). */
        String run(Connection c) throws SQLException;
    }

    private Migrations() { }

    /**
     * Applique {@code work} si la migration {@code id} n'a jamais tourné.
     *
     * @return {@code true} si elle vient d'être appliquée ici.
     */
    public static boolean runOnce(Database db, String id, Work work) {
        try (Connection c = db.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS core_migrations ("
                        + "  id         VARCHAR(96) NOT NULL PRIMARY KEY,"
                        + "  applied_at BIGINT NOT NULL,"
                        + "  summary    VARCHAR(255) NOT NULL DEFAULT ''"
                        + ")");
            }
            if (isApplied(c, id)) return false;

            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                String summary = work.run(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO core_migrations (id, applied_at, summary) VALUES (?,?,?)")) {
                    ps.setString(1, id);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, summary == null ? "" : truncate(summary, 255));
                    ps.executeUpdate();
                }
                c.commit();
                LOG.info("[Migration] " + id + " appliquée : " + summary);
                return true;
            } catch (SQLException e) {
                c.rollback();
                // Marqueur déjà posé par l'autre serveur entre-temps : rien à faire.
                if (isApplied(c, id)) return false;
                LOG.severe("[Migration] " + id + " annulée : " + e.getMessage());
                return false;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            LOG.severe("[Migration] " + id + " impossible : " + e.getMessage());
            return false;
        }
    }

    private static boolean isApplied(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM core_migrations WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
