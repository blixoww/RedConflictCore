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
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * La colonne {@code rc_factions.points} est-elle en place ? Elle est arrivee apres les autres :
     * une base deja deployee ne l'a pas tant que la migration SQL n'est pas passee, et le miroir
     * doit continuer de tourner sans elle plutot que d'echouer a chaque cycle.
     */
    private volatile boolean pointsColumn;

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
        ensurePointsColumn();

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
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        String factions =
                "CREATE TABLE IF NOT EXISTS rc_factions ("
              + "  name       VARCHAR(64) NOT NULL PRIMARY KEY,"
              + "  members    INT         NOT NULL DEFAULT 0,"
              + "  kills      BIGINT      NOT NULL DEFAULT 0,"
              + "  deaths     BIGINT      NOT NULL DEFAULT 0,"
              + "  balance    BIGINT      NOT NULL DEFAULT 0,"
              + "  pb         BIGINT      NOT NULL DEFAULT 0,"
              // Points de classement de FactionEvent, remontés par le pont de RedFaction.
              // 0 partout si l'intégration n'est pas là : la colonne existe quand même, le site
              // n'a pas à savoir quels plugins tournent en jeu.
              + "  points     BIGINT      NOT NULL DEFAULT 0,"
              + "  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP"
              + "             ON UPDATE CURRENT_TIMESTAMP,"
              + "  KEY idx_rc_factions_kills  (kills),"
              + "  KEY idx_rc_factions_points (points)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

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

    /**
     * Vérifie la présence de {@code rc_factions.points} et tente de l'ajouter si elle manque.
     *
     * <p>La colonne porte les points de classement de FactionEvent. Elle est arrivée après le
     * reste du schéma : sur une base déjà en service, elle n'existe qu'une fois
     * {@code sql/004-faction-points.sql} passé. Le compte du plugin n'a normalement pas le droit
     * {@code ALTER} — comme pour {@code CREATE}, l'échec n'est pas une anomalie : on affiche le SQL
     * à passer à la main, et le miroir continue sans la colonne.
     */
    private void ensurePointsColumn() {
        if (hasPointsColumn()) {
            pointsColumn = true;
            return;
        }

        String alter = "ALTER TABLE rc_factions "
                     + "ADD COLUMN IF NOT EXISTS points BIGINT NOT NULL DEFAULT 0, "
                     + "ADD INDEX IF NOT EXISTS idx_rc_factions_points (points)";
        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(alter);
        } catch (SQLException ignored) {
            // Droit ALTER absent : c'est le réglage recommandé, on le dit plus bas.
        }

        pointsColumn = hasPointsColumn();
        if (!pointsColumn) {
            plugin.getLogger().warning("[SiteSync] Colonne rc_factions.points absente : le classement "
                    + "du site restera sur les éliminations. Passe cette requête avec un compte "
                    + "administrateur :");
            plugin.getLogger().warning(alter + ";");
        }
    }

    /** La colonne des points de classement est-elle lisible ? */
    private boolean hasPointsColumn() {
        try (Connection c = site.getConnection(); Statement st = c.createStatement()) {
            st.executeQuery("SELECT points FROM rc_factions LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            return false;
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
     *
     * <p>Seuls les points de classement ne se déduisent pas des profils : ils appartiennent à
     * FactionEvent, et arrivent par le pont déjà en place côté RedFaction (voir
     * {@link #factionPoints()}). Absent, ce pont laisse simplement la colonne à zéro.
     */
    private int syncFactions() throws SQLException {
        String read =
                "SELECT faction, COUNT(*) AS members, SUM(kills) AS kills, "
              + "       SUM(deaths) AS deaths, SUM(balance) AS balance, SUM(pb) AS pb "
              + "FROM player_profiles "
              + "WHERE faction IS NOT NULL AND faction <> '' "
              + "GROUP BY faction";

        // Nouvelle tentative à chaque passage tant que la colonne manque : la migration SQL peut
        // être passée après le démarrage du serveur, sans avoir à le redémarrer pour autant.
        if (!pointsColumn) pointsColumn = hasPointsColumn();

        String write = pointsColumn
                ? "INSERT INTO rc_factions (name, members, kills, deaths, balance, pb, points) "
                + "VALUES (?,?,?,?,?,?,?)"
                : "INSERT INTO rc_factions (name, members, kills, deaths, balance, pb) "
                + "VALUES (?,?,?,?,?,?)";

        Map<String, Integer> points =
                pointsColumn ? factionPoints() : Collections.<String, Integer>emptyMap();

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
                    String tag = r.getString("faction");
                    ws.setString(1, tag);
                    ws.setInt(2, r.getInt("members"));
                    ws.setLong(3, r.getLong("kills"));
                    ws.setLong(4, r.getLong("deaths"));
                    ws.setLong(5, r.getLong("balance"));
                    ws.setLong(6, r.getLong("pb"));
                    if (pointsColumn) {
                        Integer p = points.get(tag);
                        ws.setLong(7, p == null ? 0L : p.longValue());
                    }
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

    // ── Points de classement (FactionEvent) ──────────────────────────────

    /**
     * Points de classement par tag de faction, ou une carte vide s'il n'y a rien à lire.
     *
     * <p>Rien de neuf n'est inventé ici : FactionEvent publie déjà son classement dans le
     * {@code RankingProvider} de RedFaction, pour que {@code /f show} l'affiche. On lit la même
     * source, ce qui garantit que le site et le jeu montrent le même ordre, et qu'aucun des trois
     * plugins n'a besoin de connaître les deux autres.
     *
     * <p>Aucune dépendance ajoutée : sans RedFaction le hook est coupé et les classes ne sont
     * même pas chargées ; sans FactionEvent le fournisseur vaut {@code null}. Dans les deux cas la
     * colonne reste à zéro et le reste du miroir ne change pas d'un iota.
     *
     * <p>La lecture repasse par le thread principal : elle traverse les collections de RedFaction et
     * le fichier {@code classement.yml} de FactionEvent, qui ne sont pas faits pour être lus depuis
     * la tâche asynchrone du miroir.
     */
    private Map<String, Integer> factionPoints() {
        if (!fr.redconflict.faction.FactionHook.isEnabled()) return Collections.emptyMap();
        // Passage d'extinction : on est déjà sur le thread principal, et l'ordonnanceur
        // n'accepte plus rien. Lire directement est ici la seule façon de lire.
        if (Bukkit.isPrimaryThread()) return readRanking();
        try {
            Future<Map<String, Integer>> snapshot = Bukkit.getScheduler().callSyncMethod(plugin,
                    new Callable<Map<String, Integer>>() {
                        @Override public Map<String, Integer> call() {
                            return readRanking();
                        }
                    });
            return snapshot.get(5L, TimeUnit.SECONDS);
        } catch (Throwable t) {
            // Y compris l'interruption et le délai dépassé : le classement du site vaut
            // largement moins cher qu'un cycle de miroir perdu.
            plugin.getLogger().warning("[SiteSync] Classement des factions illisible ("
                    + t + ") — points laissés à zéro pour ce passage.");
            return Collections.emptyMap();
        }
    }

    /**
     * Interroge le fournisseur de classement de RedFaction. À n'appeler que depuis le thread
     * principal, et seulement si {@link fr.redconflict.faction.FactionHook#isEnabled()} est vrai :
     * c'est la seule méthode d'ici qui touche aux classes {@code fr.redfaction.*}.
     */
    private static Map<String, Integer> readRanking() {
        if (!fr.redfaction.api.RedFactionAPI.isAvailable()) return Collections.emptyMap();

        fr.redfaction.api.RedFactionAPI api = fr.redfaction.api.RedFactionAPI.get();
        fr.redfaction.api.RankingProvider ranking = api.getRankingProvider();
        // Personne n'a enregistré de classement : FactionEvent est absent ou désactivé.
        if (ranking == null) return Collections.emptyMap();

        // Insensible à la casse : le tag des profils vient du même getTag(), mais un écart de
        // casse ne doit pas faire disparaître les points d'une faction.
        Map<String, Integer> points = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        for (fr.redfaction.entity.Faction faction : api.getNormalFactions()) {
            String tag = faction.getTag();
            if (tag == null || tag.isEmpty()) continue;
            points.put(tag, ranking.getPoints(faction));
        }
        return points;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
