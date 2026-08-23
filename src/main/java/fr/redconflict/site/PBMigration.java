package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.db.Database;
import fr.redconflict.pb.SitePBLedger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Transfert unique des Points Boutique de H2 vers la bourse d'Azuriom.
 *
 * <p>Les PB vivaient dans {@code player_profiles.pb} ; ils vivent désormais dans
 * {@code users.money}. Sans ce passage, tout le monde repartirait de zéro le jour
 * de la bascule.
 *
 * <p><b>Rejouable sans danger.</b> Chaque joueur migré laisse une ligne
 * {@code source = 'migration'} dans {@code rc_pb_log} ; un second passage la voit
 * et saute le joueur. C'est ce qui permet de relancer la commande après l'avoir
 * interrompue, ou de rattraper les comptes créés entre-temps, sans créditer
 * personne deux fois.
 *
 * <p><b>H2 n'est pas vidé.</b> La colonne d'origine reste telle quelle : c'est ce
 * qui rend le repli {@code pb.ledger: h2} encore possible en cas de problème, et
 * ce qui laisse une trace vérifiable de ce que chacun possédait avant.
 */
public final class PBMigration {

    /** Résultat d'un passage, pour le rapport à l'administrateur. */
    public static final class Report {
        public int migrated;
        public int alreadyDone;
        public int noAccount;
        public int failed;
        public long totalPB;

        @Override
        public String toString() {
            return migrated + " migrés (" + totalPB + " PB), " + alreadyDone + " déjà faits, "
                 + noAccount + " sans compte Azuriom, " + failed + " en échec";
        }
    }

    private final RedConflictCore plugin;
    private final Database h2;
    private final SiteDatabase site;

    public PBMigration(RedConflictCore plugin, Database h2, SiteDatabase site) {
        this.plugin = plugin;
        this.h2 = h2;
        this.site = site;
    }

    /**
     * Exécute la migration. À lancer depuis un thread asynchrone : elle balaie
     * toute la table des profils.
     *
     * @param dryRun n'écrit rien, compte seulement ce qui serait fait
     */
    public Report run(boolean dryRun) {
        Report report = new Report();
        if (!site.isAvailable() || !h2.isAvailable()) {
            plugin.getLogger().severe("[Migration PB] Une des deux bases est injoignable — rien n'a été fait.");
            report.failed = -1;
            return report;
        }

        List<String[]> profiles = readH2Profiles();
        if (profiles.isEmpty()) {
            plugin.getLogger().info("[Migration PB] Aucun profil avec des PB à transférer.");
            return report;
        }

        Set<String> alreadyMigrated = readMigratedUuids();

        for (String[] row : profiles) {
            String uuid = row[0];
            String name = row[1];
            int pb;
            try { pb = Integer.parseInt(row[2]); } catch (NumberFormatException e) { continue; }

            if (alreadyMigrated.contains(uuid)) { report.alreadyDone++; continue; }
            if (pb <= 0) continue;

            if (dryRun) { report.migrated++; report.totalPB += pb; continue; }

            switch (credit(uuid, name, pb)) {
                case 1:  report.migrated++; report.totalPB += pb; break;
                case 0:  report.noAccount++; break;
                default: report.failed++; break;
            }
        }

        plugin.getLogger().info("[Migration PB] " + (dryRun ? "(simulation) " : "") + report);
        return report;
    }

    /** @return 1 crédité, 0 pas de compte Azuriom, -1 erreur SQL */
    private int credit(String uuid, String name, int pb) {
        Connection c = null;
        boolean previousAutoCommit = true;
        try {
            c = site.getConnection();
            previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);

            // Le crédit et sa marque partent ensemble : une coupure de courant
            // entre les deux, et le joueur serait crédité deux fois au prochain
            // passage.
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET money = money + ? WHERE game_id = ?")) {
                ps.setInt(1, pb);
                ps.setString(2, uuid.replace("-", ""));
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                c.rollback();
                plugin.getLogger().warning("[Migration PB] " + name + " (" + uuid
                        + ") n'a pas de compte Azuriom — " + pb + " PB en attente.");
                return 0;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO rc_pb_log (uuid, name, delta, balance, source, reason) "
                  + "VALUES (?,?,?,?, 'migration', 'Transfert H2 -> users.money')")) {
                ps.setString(1, uuid);
                ps.setString(2, name != null ? name : "");
                ps.setInt(3, pb);
                // Le solde d'après n'est pas relu : une requête de plus par joueur
                // pour une valeur qui n'a d'intérêt qu'historique.
                ps.setInt(4, pb);
                ps.executeUpdate();
            }

            c.commit();
            return 1;
        } catch (SQLException e) {
            rollbackQuietly(c);
            plugin.getLogger().warning("[Migration PB] " + name + " : " + e.getMessage());
            return -1;
        } finally {
            closeQuietly(c, previousAutoCommit);
        }
    }

    private List<String[]> readH2Profiles() {
        List<String[]> out = new ArrayList<>();
        try (Connection c = h2.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, name, pb FROM player_profiles WHERE pb > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new String[] { rs.getString("uuid"), rs.getString("name"),
                        String.valueOf(rs.getInt("pb")) });
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Migration PB] Lecture H2 impossible : " + e.getMessage());
        }
        return out;
    }

    private Set<String> readMigratedUuids() {
        Set<String> out = new HashSet<>();
        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT uuid FROM rc_pb_log WHERE source = 'migration'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            plugin.getLogger().severe("[Migration PB] Lecture des migrations déjà faites impossible : "
                    + e.getMessage());
            // Renvoyer un ensemble vide ferait tout recréditer. On préfère
            // signaler l'échec par une exception plutôt que doubler les soldes.
            throw new IllegalStateException("rc_pb_log illisible — migration interrompue", e);
        }
        return out;
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

    /** Utile pour un log de contrôle : le format attendu par {@code users.game_id}. */
    public static String gameId(String dashedUuid) {
        return SitePBLedger.gameId(java.util.UUID.fromString(dashedUuid));
    }
}
