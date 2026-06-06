package fr.originsfight.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.originsfight.OriginsFightCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.h2.tools.Server;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provider central de connexions vers la base H2 partagée (mode serveur TCP local).
 *
 * <p>Architecture multi-serveur : tous les serveurs Minecraft (Faction, Minage, HUB) tournent sur
 * la même machine et partagent UNE base H2. Un seul serveur héberge le serveur H2 TCP
 * ({@code database.server.enabled: true}, typiquement le HUB) ; les autres s'y connectent en client TCP.
 *
 * <p>Les connexions sont gérées par un pool HikariCP : chaque opération SQL emprunte une connexion
 * via {@link #getConnection()} (à utiliser en try-with-resources) et la rend immédiatement au pool.
 *
 * <p>URL de connexion : {@code jdbc:h2:tcp://<host>:<port>/./<name>;MODE=PostgreSQL}.
 * Le mode PostgreSQL facilite une éventuelle migration future vers un vrai PostgreSQL.
 *
 * <p>Note shade : seul HikariCP est relocé ({@code com.zaxxer.hikari → fr.originsfight.libs.hikari}).
 * H2 N'EST PAS relocé (il charge ses ressources via des chemins {@code org/h2/...} codés en dur
 * que le shade ne réécrirait pas) — il reste isolé par le classloader du plugin.
 */
public class Database {

    private final OriginsFightCore plugin;

    private HikariDataSource dataSource;
    private Server tcpServer;       // non-null uniquement si ce serveur héberge H2
    private String serverId;        // identifiant de CE serveur (faction|minage|hub)
    private boolean kickOnConflict;

    public Database(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    /**
     * Démarre (si nécessaire) le serveur H2 TCP puis le pool de connexions.
     *
     * @return {@code true} si la base est joignable, {@code false} sinon (les modules DB doivent
     *         alors se désactiver proprement).
     */
    public boolean start() {
        FileConfiguration cfg = plugin.getConfig();

        boolean serverEnabled = cfg.getBoolean("database.server.enabled", false);
        String host           = cfg.getString("database.host", "localhost");
        int port              = cfg.getInt("database.port", 9092);
        String name           = cfg.getString("database.name", "central");
        String user           = cfg.getString("database.user", "sa");
        String password       = cfg.getString("database.password", "");
        int minIdle           = cfg.getInt("database.pool.minimum-idle", 2);
        int maxSize           = cfg.getInt("database.pool.maximum-size", 10);
        this.serverId         = cfg.getString("database.server-id", "default");
        this.kickOnConflict   = cfg.getBoolean("database.lock.kick-on-conflict", false);

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
                // Pas de -tcpAllowOthers : la base n'accepte que les connexions locales
                // (tous les serveurs Minecraft sont sur la même machine). Sécurité.
                tcpServer = Server.createTcpServer(
                        "-tcpPort", String.valueOf(port),
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
            // Mode embarqué : la base est un simple fichier local, aucun port réseau nécessaire.
            // AUTO_SERVER=FALSE : pas de serveur TCP automatique (évite les conflits de port).
            String dbFilePath = dataDir.getAbsolutePath().replace('\\', '/') + "/" + name;
            jdbcUrl = "jdbc:h2:file:" + dbFilePath + ";MODE=PostgreSQL;AUTO_SERVER=FALSE";
            plugin.getLogger().info("[H2] Mode embarqué (fichier) : " + jdbcUrl);
        } else {
            // Mode TCP : connexion vers un serveur H2 existant (local ou distant).
            jdbcUrl = "jdbc:h2:tcp://" + host + ":" + port + "/./data/" + name + ";MODE=PostgreSQL";
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        // Driver H2 embarqué dans le jar du plugin (non relocé).
        hc.setDriverClassName(org.h2.Driver.class.getName());
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
                if (dataSource != null) { dataSource.close(); dataSource = null; }
                plugin.getLogger().warning("[H2] Connexion impossible (tentative " + attempt + "/"
                        + maxAttempts + ") : " + e.getMessage());
                if (attempt < maxAttempts) {
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        plugin.getLogger().severe("[H2] Échec de connexion à la base H2 après " + maxAttempts + " tentatives.");
        return false;
    }

    /** Ferme le pool puis, le cas échéant, arrête le serveur H2 TCP hébergé. */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            try { dataSource.close(); } catch (Exception ignored) {}
        }
        dataSource = null;
        if (tcpServer != null) {
            try { tcpServer.stop(); plugin.getLogger().info("[H2] Serveur H2 TCP arrêté."); }
            catch (Exception ignored) {}
            tcpServer = null;
        }
    }

    // ── API ────────────────────────────────────────────────────────────────────

    /** Emprunte une connexion au pool. À UTILISER EN TRY-WITH-RESOURCES (rend la connexion au pool). */
    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("Pool H2 non initialisé.");
        return dataSource.getConnection();
    }

    /** {@code true} si le pool est opérationnel. */
    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    /** Identifiant de CE serveur dans la grappe (faction|minage|hub…). */
    public String getServerId() {
        return serverId;
    }

    /** {@code true} si un conflit de verrou doit entraîner le kick du joueur. */
    public boolean isKickOnConflict() {
        return kickOnConflict;
    }
}
