package fr.redconflict.site;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.redconflict.RedConflictCore;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Pool partagé vers la base du site (MariaDB, base {@code azuriom}).
 *
 * <p>Un seul pool pour tous les usages du pont : le miroir des profils
 * ({@link SiteSync}), le ledger des Points Boutique
 * ({@link fr.redconflict.pb.SitePBLedger}), les droits possédés
 * ({@link EntitlementService}), le catalogue ({@link CatalogExporter}) et les
 * commandes web ({@link SiteDeliverCommand}). Chacun ouvrait le sien, on
 * multipliait les connexions inactives pour rien.
 *
 * <p><b>Ce pool n'est pas facultatif quand les PB y vivent.</b> Tant que le
 * ledger était dans H2, une base du site injoignable ne coûtait qu'un classement
 * périmé ; depuis que le solde y habite, elle coûte la boutique. C'est le prix
 * assumé du solde unique : voir l'en-tête de {@code sql/001-site-bridge.sql}.
 * Le reste du gameplay — inventaires, factions, stats — continue de tourner sur
 * H2 et n'est jamais concerné.
 */
public final class SiteDatabase {

    private final RedConflictCore plugin;

    private HikariDataSource pool;
    private String url;

    public SiteDatabase(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    /**
     * Ouvre le pool si {@code site.enabled} est vrai en configuration.
     *
     * @return {@code true} si la base est joignable et le pont utilisable.
     */
    public boolean start() {
        FileConfiguration cfg = plugin.getConfig();

        if (!cfg.getBoolean("site.enabled", false)) {
            plugin.getLogger().info("[Site] Pont désactivé (site.enabled: false).");
            return false;
        }

        this.url = cfg.getString("site.url", "jdbc:mariadb://172.18.0.1:3306/azuriom");
        String user = cfg.getString("site.user", "rc_sync");
        String password = cfg.getString("site.password", "");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(password);
        // Le ledger PB est sur le chemin critique d'un achat : il lui faut de la
        // marge, contrairement aux deux connexions que suffisaient au miroir.
        hc.setMaximumPoolSize(cfg.getInt("site.pool-size", 6));
        hc.setMinimumIdle(1);
        hc.setPoolName("RedConflict-Site");
        hc.setConnectionTimeout(8_000L);
        // Plus court que le wait_timeout par défaut de MariaDB (8 h) : une
        // connexion inactive coupée côté serveur ferait échouer l'opération.
        hc.setMaxLifetime(600_000L);
        hc.setConnectionTestQuery("SELECT 1");

        try {
            pool = new HikariDataSource(hc);
            try (Connection c = pool.getConnection()) {
                if (!c.isValid(5)) throw new SQLException("connexion invalide");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[Site] Base du site injoignable : " + e.getMessage());
            plugin.getLogger().severe("[Site] La boutique PB sera indisponible tant que ce n'est pas réglé.");
            close();
            return false;
        }

        plugin.getLogger().info("[Site] Pont ouvert vers " + url);
        return true;
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            try { pool.close(); } catch (Exception ignored) { }
        }
        pool = null;
    }

    // ── Accès ──────────────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return pool != null && !pool.isClosed();
    }

    public Connection getConnection() throws SQLException {
        if (!isAvailable()) throw new SQLException("Pont vers la base du site fermé.");
        return pool.getConnection();
    }

    public String getUrl() {
        return url;
    }
}
