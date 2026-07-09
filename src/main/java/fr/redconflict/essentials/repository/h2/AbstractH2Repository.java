package fr.redconflict.essentials.repository.h2;

import fr.redconflict.db.Database;
import fr.redconflict.essentials.model.StoredLocation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Code commun des repositories H2 : accès au pool, DDL, lecture des colonnes
 * de position. Toutes les requêtes empruntent une connexion au pool Hikari
 * en try-with-resources (voir {@link Database#getConnection()}).
 */
abstract class AbstractH2Repository {

    protected final Database db;
    protected final Logger logger;

    protected AbstractH2Repository(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /** Exécute le DDL d'initialisation. @return false si la base est indisponible. */
    protected boolean createTable(String ddl) {
        if (db == null || !db.isAvailable()) return false;
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddl);
            return true;
        } catch (SQLException e) {
            logger.severe("[Essentials] Échec DDL : " + e.getMessage());
            return false;
        }
    }

    /** Lit les colonnes world/x/y/z/yaw/pitch du ResultSet courant. */
    protected StoredLocation readLocation(ResultSet rs) throws SQLException {
        return new StoredLocation(
                rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"));
    }

    /** Renseigne les 6 paramètres de position à partir de l'index donné. */
    protected void bindLocation(PreparedStatement ps, int startIndex, StoredLocation loc) throws SQLException {
        ps.setString(startIndex, loc.getWorldName());
        ps.setDouble(startIndex + 1, loc.getX());
        ps.setDouble(startIndex + 2, loc.getY());
        ps.setDouble(startIndex + 3, loc.getZ());
        ps.setFloat(startIndex + 4, loc.getYaw());
        ps.setFloat(startIndex + 5, loc.getPitch());
    }

    /** Colonnes de position communes aux tables de localisation. */
    protected static String locationColumns() {
        return "world VARCHAR(64) NOT NULL, "
                + "x DOUBLE PRECISION NOT NULL, "
                + "y DOUBLE PRECISION NOT NULL, "
                + "z DOUBLE PRECISION NOT NULL, "
                + "yaw REAL NOT NULL, "
                + "pitch REAL NOT NULL";
    }

    protected void logError(String operation, SQLException e) {
        logger.severe("[Essentials] " + operation + " : " + e.getMessage());
    }
}
