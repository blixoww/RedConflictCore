package fr.redconflict.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.redconflict.RedConflictCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.h2.Driver;
import org.h2.tools.Server;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provider central de connexions vers la base H2 partagée (mode serveur TCP local).
 *
 * <p>Architecture multi-serveur : les serveurs Minecraft qui font tourner RedConflictCore
 * (Faction, Minage) tournent sur la même machine et partagent UNE base H2. Un seul serveur
 * héberge le serveur H2 TCP ({@code database.server.enabled: true}) : c'est le FACTION, car
 * le HUB est un lobby verrouillé sans RedConflictCore. Les autres serveurs (Minage) s'y
 * connectent en client TCP avec {@code host: 127.0.0.1} (PAS {@code localhost} : voir le
 * piège documenté dans {@link #start()}).
 *
 * <p>Les connexions sont gérées par un pool HikariCP : chaque opération SQL emprunte une connexion
 * via {@link #getConnection()} (à utiliser en try-with-resources) et la rend immédiatement au pool.
 *
 * <p>URL de connexion : {@code jdbc:h2:tcp://<host>:<port>/./<name>;MODE=PostgreSQL}.
 * Le mode PostgreSQL facilite une éventuelle migration future vers un vrai PostgreSQL.
 *
 * <p>Note shade : seul HikariCP est relocé ({@code com.zaxxer.hikari → fr.redconflict.libs.hikari}).
 * H2 N'EST PAS relocé (il charge ses ressources via des chemins {@code org/h2/...} codés en dur
 * que le shade ne réécrirait pas) — il reste isolé par le classloader du plugin.
 */
public class Database {

    private final RedConflictCore plugin;

    private HikariDataSource dataSource;
    private Server tcpServer;       // non-null uniquement si ce serveur héberge H2
    private String serverId;        // identifiant de CE serveur (faction|minage|hub)
    private boolean kickOnConflict;
    private long lockWaitMillis;

    public Database(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    /**
     * Démarre (si nécessaire) le serveur H2 TCP puis le pool de connexions.
     *
     * @return {@code true} si la base est joignable, {@code false} sinon (les modules DB doivent
     * alors se désactiver proprement).
     */
    public boolean start() {
        FileConfiguration cfg = plugin.getConfig();

        boolean serverEnabled = cfg.getBoolean("database.server.enabled", false);
        String host = cfg.getString("database.host", "localhost");
        int port = cfg.getInt("database.port", 9092);
        String name = cfg.getString("database.name", "central");
        String user = cfg.getString("database.user", "sa");
        String password = cfg.getString("database.password", "");
        int minIdle = cfg.getInt("database.pool.minimum-idle", 2);
        int maxSize = cfg.getInt("database.pool.maximum-size", 10);
        this.serverId = cfg.getString("database.server-id", "default");
        this.kickOnConflict = cfg.getBoolean("database.lock.kick-on-conflict", false);
        this.lockWaitMillis = cfg.getLong("database.lock.wait-ms", 4000L);

        // baseDir du serveur H2 = dossier du plugin ; l'URL "./data/<name>" résout donc
        // vers <dataFolder>/data/<name>.mv.db.
        File baseDir = plugin.getDataFolder();
        if (!baseDir.exists()) baseDir.mkdirs();
        // Sous-dossier "data" pour le fichier de base (./data/<name> dans l'URL JDBC).
        File dataDir = new File(baseDir, "data");
        if (!dataDir.exists()) dataDir.mkdirs();

        // 1) Démarrage du serveur H2 TCP (un seul serveur de la grappe).
        if (serverEnabled) {
            try {
                // -tcpAllowOthers est indispensable dès que les serveurs tournent dans des
                // conteneurs distincts (Pterodactyl) : pour H2, le Minage n'est alors plus
                // "la même machine" que le Faction, et sans cette option ses connexions sont
                // refusees. Sans conteneurs, elle n'etait pas necessaire.
                tcpServer = Server.createTcpServer(
                        "-tcpPort", String.valueOf(port),
                        "-tcpAllowOthers",
                        "-ifNotExists",
                        "-baseDir", baseDir.getAbsolutePath()
                ).start();
                plugin.getLogger().info("[H2] Serveur H2 TCP démarré sur le port " + port
                        + " (baseDir=" + baseDir.getAbsolutePath() + ").");
            } catch (SQLException e) {
                plugin.getLogger().severe("[H2] Impossible de démarrer le serveur H2 TCP : " + e.getMessage());
                return false;
            }
        }

        // 2) Construction de l'URL JDBC selon le mode :
        //    - server.enabled=true  → TCP (multi-serveur, ce serveur héberge H2)
        //    - server.enabled=false + host=localhost → EMBEDDED (serveur standalone, pas de TCP)
        //    - server.enabled=false + host distant   → TCP client pur (un autre serveur héberge H2)
        final String jdbcUrl;
        if (!serverEnabled && "localhost".equalsIgnoreCase(host)) {
            // ⚠️ PIÈGE MULTI-SERVEUR : enabled=false + host=localhost = base FICHIER ISOLÉE.
            // Un serveur CLIENT de la grappe (ex. le Minage) DOIT utiliser host: 127.0.0.1,
            // sinon il crée sa propre base locale et NE voit PAS la base du Faction
            // (inventaire, PB, HDV… non synchronisés).
            // Mode embarqué : la base est un simple fichier local, aucun port réseau nécessaire.
            // AUTO_SERVER=FALSE : pas de serveur TCP automatique (évite les conflits de port).
            String dbFilePath = dataDir.getAbsolutePath().replace('\\', '/') + "/" + name;
            jdbcUrl = "jdbc:h2:file:" + dbFilePath + ";MODE=PostgreSQL;AUTO_SERVER=FALSE";
            plugin.getLogger().info("[H2] Mode embarqué (fichier) : " + jdbcUrl);
            // Ce mode est correct pour un serveur seul, et catastrophique en grappe :
            // le Minage se retrouve avec sa propre base, donc son propre inventaire.
            // Le symptôme est exactement « deux inventaires indépendants », et rien
            // dans les logs ne le signalait jusqu'ici.
            if (plugin.getConfig().getBoolean("database.sync.enabled", true)) {
                plugin.getLogger().warning("[H2] ================================================================");
                plugin.getLogger().warning("[H2] La synchro d'inventaire est ACTIVÉE alors que la base est un");
                plugin.getLogger().warning("[H2] FICHIER LOCAL ISOLÉ (server.enabled: false + host: localhost).");
                plugin.getLogger().warning("[H2] Ce serveur (" + this.serverId + ") ne partage RIEN avec les autres :");
                plugin.getLogger().warning("[H2] inventaire, enderchest, homes, HDV, métiers… tout est en double.");
                plugin.getLogger().warning("[H2] Corriger : database.server.enabled: true sur l'hôte H2 (Faction),");
                plugin.getLogger().warning("[H2] et sur les autres database.host: 172.18.0.1 sous Pterodactyl");
                plugin.getLogger().warning("[H2] (127.0.0.1 y désigne le conteneur lui-même), 127.0.0.1 sinon.");
                plugin.getLogger().warning("[H2] ================================================================");
            }
        } else {
            // Mode TCP : connexion vers un serveur H2 existant (local ou distant).
            jdbcUrl = "jdbc:h2:tcp://" + host + ":" + port + "/./data/" + name + ";MODE=PostgreSQL";
            // Tracé explicitement : c'est la ligne qui prouve, en console, que ce
            // serveur parle bien à la base des autres et pas à un fichier à lui.
            plugin.getLogger().info("[H2] Mode " + (serverEnabled ? "hôte" : "client") + " TCP : " + jdbcUrl);
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        // Driver H2 embarqué dans le jar du plugin (non relocé).
        hc.setDriverClassName(Driver.class.getName());
        hc.setUsername(user);
        hc.setPassword(password);
        hc.setMinimumIdle(minIdle);
        hc.setMaximumPoolSize(maxSize);
        hc.setPoolName("OriginsFight-H2");
        hc.setConnectionTimeout(10_000L);
        hc.setMaxLifetime(1_800_000L);          // 30 min
        hc.setConnectionTestQuery("SELECT 1");

        // En mode embarqué, une seule tentative suffit (pas de serveur réseau à attendre).
        // En mode TCP, on retente plusieurs fois pour laisser le serveur H2 démarrer.
        final boolean isTcp = serverEnabled || !"localhost".equalsIgnoreCase(host);
        final int maxAttempts = isTcp ? 5 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                dataSource = new HikariDataSource(hc);
                try (Connection c = dataSource.getConnection()) {
                    if (c.isValid(5)) {
                        plugin.getLogger().info("[H2] Pool connecté à " + jdbcUrl
                                + " (serverId=" + serverId + ").");
                        return true;
                    }
                }
            } catch (Exception e) {
                if (dataSource != null) {
                    dataSource.close();
                    dataSource = null;
                }
                plugin.getLogger().warning("[H2] Connexion impossible (tentative " + attempt + "/"
                        + maxAttempts + ") : " + e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        plugin.getLogger().severe("[H2] Échec de connexion à la base H2 après " + maxAttempts + " tentatives.");
        return false;
    }

    /**
     * Ferme le pool puis, le cas échéant, arrête le serveur H2 TCP hébergé.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
        }
        dataSource = null;
        if (tcpServer != null) {
            try {
                tcpServer.stop();
                plugin.getLogger().info("[H2] Serveur H2 TCP arrêté.");
            } catch (Exception ignored) {
            }
            tcpServer = null;
        }
    }

    // ── API ────────────────────────────────────────────────────────────────────

    /**
     * Emprunte une connexion au pool. À UTILISER EN TRY-WITH-RESOURCES (rend la connexion au pool).
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("Pool H2 non initialisé.");
        return dataSource.getConnection();
    }

    /**
     * {@code true} si le pool est opérationnel.
     */
    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * Identifiant de CE serveur dans la grappe (faction|minage|hub…).
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * {@code true} si un conflit de verrou doit entraîner le kick du joueur.
     */
    public boolean isKickOnConflict() {
        return kickOnConflict;
    }

    /**
     * Temps d'attente maximal, en millisecondes, accordé au serveur précédent
     * pour sauvegarder et relâcher le verrou avant que le joueur n'apparaisse.
     */
    public long getLockWaitMillis() {
        return lockWaitMillis;
    }
}
