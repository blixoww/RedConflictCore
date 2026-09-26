package fr.redconflict.succes;

import fr.redconflict.db.Database;
import fr.redconflict.db.Migrations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Corrige, une fois, l'avancement des succès {@link SuccesTrigger#MONEY_SPENT}.
 *
 * <p><b>Le défaut.</b> La bourse compte ses prix en centimes et passait ce
 * montant tel quel au succès, qui compte en dollars : chaque achat en bourse
 * avançait le compteur cent fois trop. D'où « Dépensier » débloqué sans avoir
 * dépensé la somme demandée.
 *
 * <p><b>La correction.</b> Pour chaque joueur, on retire l'excédent introduit
 * par ses achats en bourse depuis l'arrivée des succès : il a été crédité de
 * {@code c} au lieu de {@code c / 100}, on retire donc {@code c − c / 100}. Ce
 * qui vient de l'hôtel des ventes (déjà en dollars) reste intact.
 *
 * <ul>
 *   <li>Succès <b>non réclamé</b> : l'avancement est corrigé, et s'il repasse
 *       sous l'objectif, le succès est reverrouillé.</li>
 *   <li>Succès <b>déjà réclamé</b> : on n'y touche pas. La récompense est
 *       versée, la reprendre serait pire que l'erreur.</li>
 * </ul>
 */
final class SuccesSpendFix {

    static final String ID = "2026-09-26-succes-money-spent-centimes";

    /**
     * Arrivée du système de succès (commit « Success Update », 19/09/2026). Les
     * achats antérieurs n'ont jamais été comptés : ils ne sont pas retirés.
     */
    private static final long SUCCES_SINCE_S = 1789776000L; // 2026-09-19 00:00 UTC

    private SuccesSpendFix() { }

    static void run(Database db, SuccesCatalog catalog) {
        final Map<String, Integer> goals = new HashMap<String, Integer>();
        for (Succes s : catalog.all()) {
            if (s.trigger == SuccesTrigger.MONEY_SPENT) goals.put(s.id, s.goal);
        }
        if (goals.isEmpty()) return;

        Migrations.runOnce(db, ID, new Migrations.Work() {
            @Override public String run(Connection c) throws SQLException {
                Map<String, Long> excess = bourseExcessByUuid(c);
                int fixed = 0, relocked = 0;

                for (Map.Entry<String, Integer> g : goals.entrySet()) {
                    List<Object[]> rows = new ArrayList<Object[]>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT uuid, progress, unlocked FROM player_succes "
                          + "WHERE succes_id = ? AND claimed = FALSE")) {
                        ps.setString(1, g.getKey());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new Object[] { rs.getString(1), rs.getInt(2), rs.getBoolean(3) });
                            }
                        }
                    }

                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE player_succes SET progress = ?, unlocked = ? "
                          + "WHERE uuid = ? AND succes_id = ? AND claimed = FALSE")) {
                        for (Object[] r : rows) {
                            String uuid = (String) r[0];
                            int progress = (Integer) r[1];
                            boolean unlocked = (Boolean) r[2];
                            Long ex = excess.get(uuid);
                            if (ex == null || ex <= 0) continue;

                            int corrected = (int) Math.max(0L, progress - ex);
                            boolean stillUnlocked = unlocked && corrected >= g.getValue();
                            if (corrected == progress && stillUnlocked == unlocked) continue;

                            up.setInt(1, corrected);
                            up.setBoolean(2, stillUnlocked);
                            up.setString(3, uuid);
                            up.setString(4, g.getKey());
                            up.addBatch();
                            fixed++;
                            if (unlocked && !stillUnlocked) relocked++;
                        }
                        up.executeBatch();
                    }
                }
                return fixed + " avancement(s) corrigé(s), " + relocked + " succès reverrouillé(s)";
            }
        });
    }

    /** Excédent crédité à tort par joueur : Σ (c − c/100) sur ses achats en bourse. */
    private static Map<String, Long> bourseExcessByUuid(Connection c) throws SQLException {
        Map<String, Long> out = new HashMap<String, Long>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_uuid, CAST(quantity AS BIGINT) * price_unit AS cost "
              + "FROM shop_transactions WHERE type = 'BUY' AND timestamp >= ?")) {
            ps.setLong(1, SUCCES_SINCE_S);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cost = rs.getLong(2);
                    long credited = Math.min(Integer.MAX_VALUE, cost);   // plafond de l'ancien (int)
                    long excess = credited - cost / 100L;
                    Long prev = out.get(rs.getString(1));
                    out.put(rs.getString(1), (prev == null ? 0L : prev) + Math.max(0L, excess));
                }
            }
        } catch (SQLException e) {
            // Bourse jamais installée sur cette base : rien à corriger.
            if (e.getMessage() != null && e.getMessage().contains("SHOP_TRANSACTIONS")) return out;
            throw e;
        }
        return out;
    }
}
