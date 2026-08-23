package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Miroir des données de jeu vers la base du site (Azuriom).
 *
 * <p><b>Sens unique.</b> H2 reste seul propriétaire des données de jeu — stats,
 * inventaires, factions ; MariaDB n'en reçoit qu'un instantané, en lecture pour
 * le site. Rien de ce que fait le site n'est relu ici.
 *
 * <p>Ce choix évite le scénario qui casse toutes les intégrations de ce type :
 * deux systèmes qui écrivent la même donnée, et un écart que plus personne ne
 * sait réconcilier.
 *
 * <p><b>Une exception, assumée : les Points Boutique.</b> Ils ne sont pas
 * répliqués mais <i>déménagés</i> — leur unique exemplaire est {@code
 * users.money}, la bourse d'Azuriom, écrite des deux côtés sous verrou de ligne
 * (voir {@link fr.redconflict.pb.SitePBLedger}). C'est le seul moyen qu'un solde
 * soit dépensable en jeu comme sur le site sans pouvoir l'être deux fois. La
 * colonne {@code rc_players.pb} n'en est qu'un reflet de confort pour les
 * classements, recopié depuis la bourse à chaque passage.
 *
 * <p><b>Un seul serveur doit activer ce module.</b> Faction et Minage partagent
 * la même base H2 : les deux le feraient tourner, ils écriraient les mêmes
 * lignes en concurrence pour rien. À activer sur celui qui héberge H2 (le
 * Faction), et à laisser à {@code false} partout ailleurs.
 *
 * <p>Tout le travail se fait hors du thread principal : la lecture H2 balaie
 * toute la table des profils, et une requête réseau vers MariaDB s'y ajoute.
 */
public final class SiteSync {

    /** Au-delà, on découpe l'envoi : un batch géant tient la connexion trop longtemps. */
    private static final int BATCH_SIZE = 500;

    private final RedConflictCore plugin;
    private final Database h2;
    private final SiteDatabase site;

    private BukkitTask task;
    private long intervalMinutes;

    public SiteSync(RedConflictCore plugin, Database h2, SiteDatabase site) {
        this.plugin = plugin;
        this.h2 = h2;
        this.site = site;
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    /**
     * Programme la synchronisation périodique sur le pool déjà ouvert par
     * {@link SiteBridgeModule}.
     *
     * @return {@code true} si le miroir est actif ; {@code false} s'il est
     *         désactivé en configuration ou si l'une des deux bases manque. Dans
     *         tous les cas le serveur démarre normalement — le site affichera
     *         simplement des chiffres datés.
     */
    public boolean start() {
        FileConfiguration cfg = plugin.getConfig();

        if (!cfg.getBoolean("site.mirror-enabled", true)) {
            plugin.getLogger().info("[SiteSync] Miroir des profils désactivé.");
            return false;
        }

        if (!h2.isAvailable()) {
            plugin.getLogger().warning("[SiteSync] Base H2 indisponible : synchronisation désactivée.");
            return false;
        }
        if (!site.isAvailable()) {
            plugin.getLogger().warning("[SiteSync] Base du site indisponible : synchronisation désactivée.");
            return false;
        }

        this.intervalMinutes = Math.max(1L, cfg.getLong("site.mirror-interval-minutes", 5L));

        ensureTables();

        // Premier passage décalé de 30 s : au démarrage, le serveur a mieux à
        // faire que de balayer la table des profils.
        long delayTicks = 30L * 20L;
        long periodTicks = intervalMinutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override public void run() { syncNow(); }
        }, delayTicks, periodTicks);

        plugin.getLogger().info("[SiteSync] Actif : instantané toutes les "
                + intervalMinutes + " min vers " + site.getUrl());
        return true;
    }

    /** Le pool appartient au pont : ici on ne coupe que la tâche périodique. */
    public void close() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) { }
            task = null;
        }
    }

    // ── Schéma ─────────────────────────────────────────────────────────────────

    /**
     * Crée les deux tables si elles manquent.
     *
     * <p>Le compte SQL du plugin n'a normalement que les droits de lecture et
     * d'écriture sur ces tables, pas {@code CREATE} — et c'est voulu : la base du
     * site contient {@code users}, donc les hachages de mots de passe et les
     * jetons de session. Un plugin de gameplay n'a rien à y faire.
     *
     * <p>L'échec n'est donc pas une anomalie : on affiche le SQL à passer à la
     * main plutôt que d'exiger un compte privilégié.
     */
    private void ensureTables() {
        // Le schéma est normalement posé par sql/001-site-bridge.sql, avec un
        // compte administrateur. Si les tables sont là, on ne tente rien : le
        // compte du plugin n'a pas le droit CREATE, et MariaDB refuse un
        // « CREATE TABLE IF NOT EXISTS » sur les privilèges avant même de
        // regarder si la table existe — on récolterait un avertissement
        // inquiétant à chaque démarrage, pour rien.
        if (tablesExist()) return;

        String players =
                "CREATE TABLE IF NOT EXISTS rc_players ("
              + "  uuid       CHAR(36)    NOT NULL PRIMARY KEY,"
              + "  name       VARCHAR(32) NOT NULL DEFAULT '',"
              + "  kills      BIGINT      NOT NULL DEFAULT 0,"
              + "  deaths     BIGINT      NOT NULL DEFAULT 0,"
              + "  playtime_s BIGINT      NOT NULL DEFAULT 0,"
              + "  balance    BIGINT      NOT NULL DEFAULT 0,"
              + "  pb         BIGINT      NOT NULL DEFAULT 0,"
              + "  faction    VARCHAR(64) NOT NULL DEFAULT '',"
              + "  rank_label VARCHAR(64) NOT NULL DEFAULT '',"
              + "  streak     INT         NOT NULL DEFAULT 0,"
              + "  bounty     BIGINT      NOT NULL DEFAULT 0,"
              + "  last_join  BIGINT      NOT NULL DEFAULT 0,"
              + "  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP"
              + "             ON UPDATE CURRENT_TIMESTAMP,"
              // Index sur les colonnes de classement : le site trie dessus.
              + "  KEY idx_rc_players_kills   (kills),"
              + "  KEY idx_rc_players_balance (balance),"
              + "  KEY idx_rc_players_faction (faction)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        String factions =
                "CREATE TABLE IF NOT EXISTS rc_factions ("
              + "  name       VARCHAR(64) NOT NULL PRIMARY KEY,"
              + "  members    INT         NOT NULL DEFAULT 0,"
              + "  kills      BIGINT      NOT NULL DEFAULT 0,"
              + "  deaths     BIGINT      NOT NULL DEFAULT 0,"
              + "  balance    BIGINT      NOT NULL DEFAULT 0,"
              + "  pb         BIGINT      NOT NULL DEFAULT 0,"
              + "  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP"
              + "             ON UPDATE CURRENT_TIMESTAMP,"
              + "  KEY idx_rc_factions_kills (kills)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(players);
            st.executeUpdate(factions);
        } catch (SQLException e) {
            plugin.getLogger().warning("[SiteSync] Création des tables impossible ("
                    + e.getMessage() + "). Si le compte SQL n'a pas le droit CREATE — "
                    + "c'est le réglage recommandé — passe ces deux requêtes à la main "
                    + "avec un compte administrateur :");
            plugin.getLogger().warning(players + ";");
            plugin.getLogger().warning(factions + ";");
        }
    }

    /** Les deux tables du miroir sont-elles déjà présentes et lisibles ? */
    private boolean tablesExist() {
        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeQuery("SELECT 1 FROM rc_players LIMIT 1").close();
            st.executeQuery("SELECT 1 FROM rc_factions LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Synchronisation ────────────────────────────────────────────────────────

    /**
     * Recopie les profils puis recalcule le classement des factions.
     *
     * <p>À n'appeler que depuis un thread asynchrone.
     */
    public void syncNow() {
        if (!site.isAvailable() || !h2.isAvailable()) return;

        long started = System.currentTimeMillis();
        int players;
        int factions;
        try {
            players = syncPlayers();
            // Avant l'agrégation : le total PB par faction se calcule sur la
            // colonne qu'on vient de réaligner, pas sur la précédente.
            refreshPbFromLedger();
            factions = syncFactions();
        } catch (SQLException e) {
            // Journalisé sans propager : une panne du site ne doit jamais
            // perturber le serveur de jeu, qui continue de tourner sur H2.
            plugin.getLogger().warning("[SiteSync] Échec de la synchronisation : " + e.getMessage());
            return;
        }

        long ms = System.currentTimeMillis() - started;
        plugin.getLogger().info("[SiteSync] " + players + " profils, "
                + factions + " factions en " + ms + " ms.");
    }

    /**
     * Recopie le solde depuis {@code users.money} dans {@code rc_players.pb}.
     *
     * <p>La colonne du miroir n'est là que pour les classements : le solde
     * lui-même est la bourse d'Azuriom, écrite par le jeu et par le site. Une
     * jointure suffit, et il n'y a rien à réconcilier — la source est unique.
     *
     * <p>La jointure retire les tirets de l'UUID : {@code rc_players} suit le
     * format de H2, {@code users.game_id} celui d'Azuriom.
     */
    private void refreshPbFromLedger() throws SQLException {
        try (Connection c = site.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                    "UPDATE rc_players p JOIN users u ON u.game_id = REPLACE(p.uuid, '-', '') "
                  + "SET p.pb = FLOOR(u.money)");
        }
    }

    /** Recopie {@code player_profiles} (H2) vers {@code rc_players} (MariaDB). */
    private int syncPlayers() throws SQLException {
        String read =
                "SELECT uuid, name, kills, deaths, playtime_s, balance, pb, faction, "
              + "       rank_label, streak, bounty, last_join FROM player_profiles";

        String write =
                "INSERT INTO rc_players (uuid, name, kills, deaths, playtime_s, balance, pb, "
              + "  faction, rank_label, streak, bounty, last_join) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
              // Un joueur déjà connu est mis à jour, jamais dupliqué. C'est ce
              // qui rend la synchronisation rejouable sans effet de bord.
              + "ON DUPLICATE KEY UPDATE "
              + "  name=VALUES(name), kills=VALUES(kills), deaths=VALUES(deaths), "
              + "  playtime_s=VALUES(playtime_s), balance=VALUES(balance), "
              // pb absent volontairement : le solde n'appartient plus à H2, la
              // colonne est réalimentée depuis rc_pb juste après (voir
              // refreshPbFromLedger). L'écraser ici ressusciterait la vieille
              // valeur figée dans player_profiles à chaque passage.
              + "  faction=VALUES(faction), rank_label=VALUES(rank_label), "
              + "  streak=VALUES(streak), bounty=VALUES(bounty), last_join=VALUES(last_join)";

        int count = 0;

        try (Connection src = h2.getConnection();
             PreparedStatement rs = src.prepareStatement(read);
             ResultSet r = rs.executeQuery();
             Connection dst = site.getConnection();
             PreparedStatement ws = dst.prepareStatement(write)) {

            boolean autoCommit = dst.getAutoCommit();
            dst.setAutoCommit(false);
            try {
                int pending = 0;
                while (r.next()) {
                    ws.setString(1, r.getString("uuid"));
                    ws.setString(2, nullToEmpty(r.getString("name")));
                    ws.setLong(3, r.getLong("kills"));
                    ws.setLong(4, r.getLong("deaths"));
                    ws.setLong(5, r.getLong("playtime_s"));
                    ws.setLong(6, r.getLong("balance"));
                    ws.setLong(7, r.getLong("pb"));
                    ws.setString(8, nullToEmpty(r.getString("faction")));
                    ws.setString(9, nullToEmpty(r.getString("rank_label")));
                    ws.setInt(10, r.getInt("streak"));
                    ws.setLong(11, r.getLong("bounty"));
                    ws.setLong(12, r.getLong("last_join"));
                    ws.addBatch();
                    count++;

                    if (++pending >= BATCH_SIZE) {
                        ws.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) ws.executeBatch();
                dst.commit();
            } catch (SQLException e) {
                dst.rollback();
                throw e;
            } finally {
                dst.setAutoCommit(autoCommit);
            }
        }
        return count;
    }

    /**
     * Recalcule {@code rc_factions} par agrégation des profils.
     *
     * <p>La faction est une colonne de {@code player_profiles} : le classement
     * s'en déduit sans rien demander au plugin de factions. Une faction dissoute
     * disparaît donc d'elle-même, sans traitement particulier.
     *
     * <p>Le vidage et le remplissage sont dans une même transaction : sans elle,
     * un visiteur tombant entre les deux verrait un classement vide.
     */
    private int syncFactions() throws SQLException {
        String read =
                "SELECT faction, COUNT(*) AS members, SUM(kills) AS kills, "
              + "       SUM(deaths) AS deaths, SUM(balance) AS balance, SUM(pb) AS pb "
              + "FROM player_profiles "
              + "WHERE faction IS NOT NULL AND faction <> '' "
              + "GROUP BY faction";

        String write =
                "INSERT INTO rc_factions (name, members, kills, deaths, balance, pb) "
              + "VALUES (?,?,?,?,?,?)";

        int count = 0;

        try (Connection src = h2.getConnection();
             PreparedStatement rs = src.prepareStatement(read);
             ResultSet r = rs.executeQuery();
             Connection dst = site.getConnection()) {

            boolean autoCommit = dst.getAutoCommit();
            dst.setAutoCommit(false);
            try (Statement clear = dst.createStatement();
                 PreparedStatement ws = dst.prepareStatement(write)) {

                // DELETE et non TRUNCATE : TRUNCATE exige le droit DROP, que le
                // compte du plugin n'a volontairement pas, et il valide
                // implicitement la transaction.
                clear.executeUpdate("DELETE FROM rc_factions");

                while (r.next()) {
                    ws.setString(1, r.getString("faction"));
                    ws.setInt(2, r.getInt("members"));
                    ws.setLong(3, r.getLong("kills"));
                    ws.setLong(4, r.getLong("deaths"));
                    ws.setLong(5, r.getLong("balance"));
                    ws.setLong(6, r.getLong("pb"));
                    ws.addBatch();
                    count++;
                }
                ws.executeBatch();
                dst.commit();
            } catch (SQLException e) {
                dst.rollback();
                throw e;
            } finally {
                dst.setAutoCommit(autoCommit);
            }
        }
        return count;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
