package fr.redconflict.network;

import fr.redconflict.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Table de présence du tab-list partagé : qui est connecté, où, et sous quelle
 * apparence.
 *
 * <p>Table {@code player_presence(uuid PK, name, server_id, display, sort_key,
 * staff_only, ping, updated_at)}, dans la même base H2
 * que le reste de la grappe — Faction et Minage y écrivent chacun leurs joueurs et
 * y lisent ceux de l'autre.
 *
 * <p><b>Pourquoi ne pas réutiliser {@code player_locks}.</b> Le verrou dit où un
 * joueur a été vu la dernière fois, pas s'il y est encore : {@code locked_at} n'est
 * jamais rafraîchi et un serveur qui plante laisse ses lignes à {@code online=TRUE}
 * jusqu'à son prochain démarrage. Un tab-list construit là-dessus afficherait des
 * fantômes pendant des heures. D'où l'horodatage rafraîchi à chaque cycle et le
 * filtre de fraîcheur à la lecture : un serveur qui se tait disparaît tout seul.
 */
public class PresenceRepository {

    private static final Logger LOG = Logger.getLogger("Presence");

    private final Database db;

    public PresenceRepository(Database db) {
        this.db = db;
    }

    /** @return {@code true} si la table est prête (sinon le tab partagé se désactive). */
    public boolean createTable() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS player_presence (" +
                 "  uuid           VARCHAR(36) PRIMARY KEY," +
                 "  name           VARCHAR(32)   NOT NULL," +
                 "  server_id      VARCHAR(64)   NOT NULL," +
                 "  display        VARCHAR(256)  NOT NULL," +
                 "  sort_key       VARCHAR(8)    NOT NULL," +
                 "  staff_only     BOOLEAN       NOT NULL DEFAULT FALSE," +
                 "  ping           INT           NOT NULL DEFAULT 0," +
                 "  updated_at     BIGINT        NOT NULL" +
                 ")")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.severe("[Presence] createTable: " + e.getMessage());
            return false;
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "CREATE INDEX IF NOT EXISTS idx_presence_server ON player_presence(server_id)")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            // L'index n'est qu'une optimisation : la table fait quelques dizaines de lignes.
            LOG.warning("[Presence] index server_id: " + e.getMessage());
        }
        dropLegacySkinColumns();
        return true;
    }

    /**
     * Retire les colonnes de skin d'une grappe déjà déployée.
     *
     * <p>La texture des joueurs distants n'est plus recopiée : le tab de la 1.8.9 ne
     * dessine pas de tête ici, et c'était deux VARCHAR(2048) réécrits toutes les deux
     * secondes pour chaque connecté. Les colonnes ne sont plus alimentées, donc les
     * laisser ne casserait rien — autant ne pas garder de champ mort dans une table
     * republiée en boucle.
     *
     * <p>Un échec est sans conséquence : la colonne reste, vide.
     */
    private void dropLegacySkinColumns() {
        for (String column : new String[]{"skin_value", "skin_signature"}) {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "ALTER TABLE player_presence DROP COLUMN IF EXISTS " + column)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warning("[Presence] drop " + column + " : " + e.getMessage());
            }
        }
    }

    /**
     * Republie d'un bloc les joueurs de CE serveur.
     *
     * <p>DELETE puis INSERT dans UNE transaction, comme le miroir du site : c'est le
     * seul schéma qui garantit qu'un joueur parti ne survit pas à son cycle, sans
     * avoir à diffuser des suppressions unitaires. Les lecteurs ne voient jamais la
     * table vide — H2 leur sert l'instantané d'avant le commit.
     *
     * <p>Chaque serveur ne touche QUE ses propres lignes. C'est ce qui rend deux
     * publications simultanées inoffensives : sans ce cloisonnement, le Faction et
     * le Minage se disputeraient les mêmes verrous de ligne toutes les deux
     * secondes. Le ménage des serveurs éteints est pour cette raison à part, dans
     * {@link #purgeStale(long)}.
     *
     * @param serverId identifiant de ce serveur
     * @param rows     ses joueurs connectés, tels que son propre tab les affiche
     * @param now      horodatage à écrire
     */
    public void publish(String serverId, List<NetworkPlayer> rows, long now) {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM player_presence WHERE server_id = ?")) {
                    del.setString(1, serverId);
                    del.executeUpdate();
                }
                if (!rows.isEmpty()) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO player_presence (uuid, name, server_id, display, sort_key," +
                            " staff_only, ping, updated_at)" +
                            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        for (NetworkPlayer p : rows) {
                            ins.setString(1, p.getUuid().toString());
                            ins.setString(2, p.getName());
                            ins.setString(3, serverId);
                            ins.setString(4, p.getDisplay());
                            ins.setString(5, p.getSortKey());
                            ins.setBoolean(6, p.isHidden());
                            ins.setInt(7, p.getPing());
                            ins.setLong(8, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.warning("[Presence] publish(" + serverId + ") : " + e.getMessage());
        }
    }

    /**
     * Les joueurs des AUTRES serveurs, rafraîchis depuis {@code freshSince}.
     *
     * <p>Ce filtre de fraîcheur remplace un « heartbeat » explicite : un serveur
     * arrêté, planté ou coupé du réseau cesse de rafraîchir ses lignes, et ses
     * joueurs quittent le tab des autres au bout d'un cycle ou deux.
     */
    public List<NetworkPlayer> fetchOthers(String serverId, long freshSince) {
        List<NetworkPlayer> out = new ArrayList<NetworkPlayer>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uuid, name, server_id, display, sort_key, staff_only, ping" +
                 " FROM player_presence" +
                 " WHERE server_id <> ? AND updated_at >= ?")) {
            ps.setString(1, serverId);
            ps.setLong(2, freshSince);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id;
                    try {
                        id = UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException bad) {
                        continue; // ligne illisible : on l'ignore plutôt que de casser le tab
                    }
                    out.add(new NetworkPlayer(
                            id,
                            rs.getString("name"),
                            rs.getString("server_id"),
                            rs.getString("display"),
                            rs.getString("sort_key"),
                            rs.getBoolean("staff_only"),
                            rs.getInt("ping")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("[Presence] fetchOthers(" + serverId + ") : " + e.getMessage());
            return Collections.emptyList();
        }
        return out;
    }

    /**
     * Ménage : retire les lignes d'un serveur qui ne publie plus depuis longtemps.
     *
     * <p>Ne corrige rien — {@link #fetchOthers} ignore déjà ces lignes par
     * fraîcheur. Ça évite juste qu'un serveur arrêté sans préavis laisse ses joueurs
     * en base indéfiniment. Appelé rarement, et hors de la transaction de
     * publication : deux serveurs peuvent viser les mêmes lignes mortes en même
     * temps, et cet accroc-là ne doit pas faire échouer une publication.
     */
    public void purgeStale(long before) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM player_presence WHERE updated_at < ?")) {
            ps.setLong(1, before);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("[Presence] " + n + " ligne(s) d'un serveur éteint nettoyée(s).");
        } catch (SQLException e) {
            LOG.warning("[Presence] purgeStale : " + e.getMessage());
        }
    }

    /**
     * Retire toutes les lignes de ce serveur.
     *
     * <p>Appelé à l'extinction : sans ça, les joueurs du serveur qui s'arrête
     * resteraient affichés chez l'autre le temps du délai de fraîcheur.
     */
    public void clear(String serverId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM player_presence WHERE server_id = ?")) {
            ps.setString(1, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[Presence] clear(" + serverId + ") : " + e.getMessage());
        }
    }
}
