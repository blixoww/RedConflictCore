package fr.redconflict.vote;

import fr.redconflict.RedConflictCore;
import fr.redconflict.pb.SitePBLedger;
import fr.redconflict.site.SiteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ce que le site sait de la disponibilité du vote, lu dans {@code rc_vote_status}.
 *
 * <p><b>Le serveur de jeu ne sait pas répondre tout seul.</b> Il ne voit qu'une
 * chose, le {@code rcvote <pseudo>} qu'AzLink lui envoie après un vote validé :
 * ni le nombre de sites déclarés, ni leurs délais, ni les vérifications par IP.
 * Le site, lui, interroge le plugin Vote et obtient la vérité — il la dépose
 * dans cette table, on la relit ici.
 *
 * <p><b>Ce qu'on lit est une date limite, pas un compte à rebours.</b> Elle ne
 * change que lorsque le joueur vote ; entre deux votes, c'est le client qui la
 * compare à l'heure courante et fait apparaître l'encart tout seul. D'où une
 * relecture peu fréquente, et aucune urgence à la rafraîchir.
 *
 * <p><b>Toutes les méthodes touchent le réseau.</b> À n'appeler que depuis un
 * thread asynchrone.
 */
public final class VoteStatusMirror {

    /** Code d'erreur MariaDB pour « table inconnue » (script 003 non passé). */
    private static final int ER_NO_SUCH_TABLE = 1146;
    /** Code d'erreur MariaDB pour « SELECT command denied » (GRANT manquant). */
    private static final int ER_TABLEACCESS_DENIED = 1142;

    /** Pause après une erreur qui demande une intervention sur la base. */
    private static final long PAUSE_STRUCTURELLE_MS = 10L * 60L * 1000L;
    /** Pause après une erreur passagère — le temps qu'une coupure réseau se résorbe. */
    private static final long PAUSE_PASSAGERE_MS = 60L * 1000L;

    private final RedConflictCore plugin;
    private final SiteDatabase site;

    /**
     * Instant avant lequel on ne retente rien.
     *
     * <p>Une table absente ou un droit manquant ne se règlent pas dans la seconde, et la
     * relecture a lieu à chaque connexion et après chaque vote : sans cette pause, une
     * cause unique remplit la console de lignes identiques. On se tait, puis on retente —
     * un {@code GRANT} passé entre-temps reprend effet sans redémarrage.
     */
    private volatile long silenceJusqua;

    public VoteStatusMirror(RedConflictCore plugin, SiteDatabase site) {
        this.plugin = plugin;
        this.site = site;
    }

    /** Statut d'un joueur tel que le site l'a calculé. */
    public static final class Statut {

        /** Nombre de sites votables au moment du calcul. */
        public final int disponibles;
        /** Epoch en secondes de la prochaine ouverture ; 0 = aucune échéance connue. */
        public final long prochainVote;

        public Statut(int disponibles, long prochainVote) {
            this.disponibles = disponibles;
            this.prochainVote = prochainVote;
        }

        /**
         * L'état d'un joueur dont le site n'a encore rien écrit.
         *
         * <p>Traité comme « votable » : la ligne n'apparaît qu'à la première
         * page chargée depuis le site, et un joueur qui n'y est jamais allé n'a
         * jamais voté. Se corrige de lui-même dès qu'il ouvre la page de vote —
         * ce que l'encart lui propose précisément de faire.
         */
        public static Statut inconnu() {
            return new Statut(1, 0L);
        }
    }

    public boolean isAvailable() {
        return System.currentTimeMillis() >= silenceJusqua && site != null && site.isAvailable();
    }

    /**
     * Lit le statut de plusieurs joueurs en une requête.
     *
     * @return les lignes trouvées ; un joueur absent de la table est absent de
     *         la map, au choix de l'appelant d'en faire un {@link Statut#inconnu()}
     */
    public Map<UUID, Statut> lire(Collection<UUID> uuids) {
        Map<UUID, Statut> resultat = new HashMap<UUID, Statut>();
        if (!isAvailable() || uuids.isEmpty()) return resultat;

        // La table est indexée sur game_id — l'UUID sans tirets, comme users.
        Map<String, UUID> parGameId = new HashMap<String, UUID>(uuids.size() * 2);
        for (UUID uuid : uuids) parGameId.put(SitePBLedger.gameId(uuid), uuid);

        StringBuilder sql = new StringBuilder(
                "SELECT game_id, available, next_vote_at FROM rc_vote_status WHERE game_id IN (");
        for (int i = 0; i < uuids.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(')');

        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID uuid : uuids) ps.setString(index++, SitePBLedger.gameId(uuid));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = parGameId.get(rs.getString("game_id"));
                    if (uuid == null) continue;
                    resultat.put(uuid, new Statut(rs.getInt("available"), rs.getLong("next_vote_at")));
                }
            }
        } catch (SQLException e) {
            signaler(e);
        }
        return resultat;
    }

    /** Code d'erreur MariaDB pour « command denied to user … for column » (GRANT de colonne). */
    private static final int ER_COLUMNACCESS_DENIED = 1143;

    /** Bilan d'un {@link #effacer(UUID)}, pour le message rendu à l'administrateur. */
    public static final class Effacement {
        /** Votes supprimés de {@code vote_votes}. */
        public final int votes;
        /** Le joueur a-t-il un compte sur le site ({@code users.game_id}) ? */
        public final boolean compteTrouve;
        /** Motif d'échec lisible, ou {@code null} si tout s'est bien passé. */
        public final String erreur;

        Effacement(int votes, boolean compteTrouve, String erreur) {
            this.votes = votes;
            this.compteTrouve = compteTrouve;
            this.erreur = erreur;
        }
    }

    /**
     * Efface <b>tout</b> l'historique de votes du joueur côté site, pour
     * {@code /rcvote reset}. Irréversible.
     *
     * <p>Deux écritures, dans une seule transaction : les votes d'Azuriom
     * ({@code vote_votes}) — c'est d'eux que le plugin Vote déduit le délai avant
     * le prochain vote et ses classements — puis la ligne de {@code rc_vote_status},
     * passée à « un site votable, aucune échéance » pour que l'encart du HUD
     * s'ouvre sans attendre. Le site la recalcule de lui-même à la prochaine page
     * chargée par le joueur : on ne fait que dire « plus rien ne bloque ».
     *
     * <p>Droits requis : {@code sql/006-vote-reset.sql}. Réseau : à n'appeler
     * que hors du thread principal.
     */
    public Effacement effacer(UUID uuid) {
        if (site == null || !site.isAvailable()) {
            return new Effacement(0, false, "pont vers le site fermé");
        }
        String gameId = SitePBLedger.gameId(uuid);

        try (Connection c = site.getConnection()) {
            Long userId = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE game_id = ?")) {
                ps.setString(1, gameId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) userId = rs.getLong(1);
                }
            }
            if (userId == null) return new Effacement(0, false, null);

            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int votes;
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM vote_votes WHERE user_id = ?")) {
                    del.setLong(1, userId);
                    votes = del.executeUpdate();
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE rc_vote_status SET available = 1, next_vote_at = 0, computed_at = ? "
                      + "WHERE game_id = ?")) {
                    upd.setLong(1, System.currentTimeMillis() / 1000L);
                    upd.setString(2, gameId);
                    upd.executeUpdate();
                }
                c.commit();
                return new Effacement(votes, true, null);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            int code = e.getErrorCode();
            if (code == ER_TABLEACCESS_DENIED || code == ER_COLUMNACCESS_DENIED) {
                return new Effacement(0, true, "droit manquant pour rc_sync — passe "
                        + "sql/006-vote-reset.sql sur la base du site");
            }
            if (code == ER_NO_SUCH_TABLE) {
                return new Effacement(0, true, "table absente (plugin Vote d'Azuriom, ou "
                        + "sql/003-vote-status.sql non passé)");
            }
            return new Effacement(0, true, e.getMessage());
        }
    }

    /**
     * Journalise une fois, puis se tait le temps de la pause.
     *
     * <p>L'encart de vote est un confort : aucune de ces erreurs ne mérite d'occuper la
     * console, et aucune n'empêche quoi que ce soit d'autre de fonctionner.
     */
    private void signaler(SQLException e) {
        String cause;
        long pause;

        switch (e.getErrorCode()) {
            case ER_NO_SUCH_TABLE:
                cause = "table rc_vote_status absente — passe sql/003-vote-status.sql"
                      + " sur la base du site";
                pause = PAUSE_STRUCTURELLE_MS;
                break;
            case ER_TABLEACCESS_DENIED:
                cause = "rc_vote_status inaccessible — table absente ou droit manquant :"
                      + " passe sql/003-vote-status.sql sur la base du site, il crée la table"
                      + " ET pose le GRANT";
                pause = PAUSE_STRUCTURELLE_MS;
                break;
            default:
                cause = "lecture du statut de vote : " + e.getMessage();
                pause = PAUSE_PASSAGERE_MS;
                break;
        }

        // Posé avant le message : les relectures déjà en vol se taisent tout de suite.
        silenceJusqua = System.currentTimeMillis() + pause;
        plugin.getLogger().warning("[Vote] " + cause + ". L'encart de vote reste masqué ;"
                + " nouvelle tentative dans " + (pause / 60000L) + " min.");
    }
}
