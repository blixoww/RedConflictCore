package fr.originsfight.shop;

import fr.originsfight.OriginsFightCore;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ShopDatabase {
    private static final Logger LOG = Logger.getLogger("Shop-DB");

    private final OriginsFightCore plugin;
    private Connection connection;

    public ShopDatabase(OriginsFightCore plugin) { this.plugin = plugin; }

    // ── Connexion ────────────────────────────────────────────────────────────

    public boolean connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "shop.db");
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            connection.setAutoCommit(true);

            try (Statement s = connection.createStatement()) {
                ResultSet rs = s.executeQuery("PRAGMA journal_mode = WAL;");
                rs.close();
                s.execute("PRAGMA busy_timeout = 10000;");
                s.execute("PRAGMA synchronous = NORMAL;");
            }
            createTables();
            LOG.info("[Shop] Base de données connectée (" + dbFile.getName() + ")");
            return true;
        } catch (Exception e) {
            LOG.severe("[Shop] EXCEPTION connexion DB: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void disconnect() {
        if (connection == null) return;
        try { if (!connection.isClosed()) connection.close(); }
        catch (SQLException e) { LOG.warning("[Shop] Erreur fermeture BDD: " + e.getMessage()); }
        finally { connection = null; }
    }

    public Connection getConnection() { return connection; }

    private void createTables() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS shop_categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL," +
                "icon_item TEXT NOT NULL DEFAULT 'minecraft:chest', sort_order INTEGER NOT NULL DEFAULT 0);");

            s.execute("CREATE TABLE IF NOT EXISTS shop_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER NOT NULL," +
                "display_name TEXT NOT NULL, minecraft_item TEXT NOT NULL, meta INTEGER NOT NULL DEFAULT 0," +
                "base_buy_price INTEGER NOT NULL DEFAULT 100, base_sell_price INTEGER NOT NULL DEFAULT 50," +
                "current_buy_price INTEGER NOT NULL DEFAULT 100, current_sell_price INTEGER NOT NULL DEFAULT 50," +
                "max_stack INTEGER NOT NULL DEFAULT 64, frozen INTEGER NOT NULL DEFAULT 0," +
                "floor_price INTEGER NOT NULL DEFAULT 1, ceil_price INTEGER NOT NULL DEFAULT 1000000," +
                "total_buy_volume INTEGER NOT NULL DEFAULT 0, total_sell_volume INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY (category_id) REFERENCES shop_categories(id));");

            // daily=1 → snapshot journalier (affiché dans le graphique)
            // daily=0 → snapshot intra-journalier (non utilisé pour le graphique)
            s.execute("CREATE TABLE IF NOT EXISTS shop_price_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL," +
                "timestamp INTEGER NOT NULL, buy_price INTEGER NOT NULL, sell_price INTEGER NOT NULL," +
                "daily INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY (item_id) REFERENCES shop_items(id));");

            // Migration : ajouter daily si la table existait déjà sans cette colonne
            try { s.execute("ALTER TABLE shop_price_history ADD COLUMN daily INTEGER NOT NULL DEFAULT 0;"); }
            catch (SQLException ignored) {}

            s.execute("CREATE TABLE IF NOT EXISTS shop_transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL," +
                "item_id INTEGER NOT NULL, type TEXT NOT NULL, quantity INTEGER NOT NULL," +
                "price_unit INTEGER NOT NULL, timestamp INTEGER NOT NULL," +
                "FOREIGN KEY (item_id) REFERENCES shop_items(id));");
        }
    }

    // ── Catégories ───────────────────────────────────────────────────────────

    public List<ShopCategory> getCategories() {
        List<ShopCategory> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,name,icon_item FROM shop_categories ORDER BY sort_order,id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                list.add(new ShopCategory(rs.getInt("id"), rs.getString("name"), rs.getString("icon_item")));
        } catch (SQLException e) { LOG.severe("[Shop] getCategories: " + e.getMessage()); }
        return list;
    }

    public int createCategory(String name, String iconItem, int sortOrder) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shop_categories(name,icon_item,sort_order) VALUES(?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name); ps.setString(2, iconItem); ps.setInt(3, sortOrder);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { if (k.next()) return k.getInt(1); }
        } catch (SQLException e) { LOG.severe("[Shop] createCategory: " + e.getMessage()); }
        return -1;
    }

    // ── Items ────────────────────────────────────────────────────────────────

    public List<ShopItem> getItemsByCategory(int categoryId) {
        List<ShopItem> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.*,c.name as category_name FROM shop_items i " +
                "JOIN shop_categories c ON i.category_id=c.id WHERE i.category_id=? ORDER BY i.id")) {
            ps.setInt(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(readItem(rs)); }
        } catch (SQLException e) { LOG.severe("[Shop] getItemsByCategory: " + e.getMessage()); }
        return list;
    }

    public List<ShopItem> getAllItems() {
        List<ShopItem> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.*,c.name as category_name FROM shop_items i " +
                "JOIN shop_categories c ON i.category_id=c.id ORDER BY i.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(readItem(rs));
        } catch (SQLException e) { LOG.severe("[Shop] getAllItems: " + e.getMessage()); }
        return list;
    }

    public ShopItem getItemById(int itemId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.*,c.name as category_name FROM shop_items i " +
                "JOIN shop_categories c ON i.category_id=c.id WHERE i.id=?")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return readItem(rs); }
        } catch (SQLException e) { LOG.severe("[Shop] getItemById: " + e.getMessage()); }
        return null;
    }

    public int createItem(int categoryId, String displayName, String minecraftItem, int meta,
                          long baseBuy, long baseSell, int maxStack, long floor, long ceil) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shop_items(category_id,display_name,minecraft_item,meta," +
                "base_buy_price,base_sell_price,current_buy_price,current_sell_price," +
                "max_stack,floor_price,ceil_price) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, categoryId); ps.setString(2, displayName); ps.setString(3, minecraftItem);
            ps.setInt(4, meta); ps.setLong(5, baseBuy); ps.setLong(6, baseSell);
            ps.setLong(7, baseBuy); ps.setLong(8, baseSell);
            ps.setInt(9, maxStack); ps.setLong(10, floor); ps.setLong(11, ceil);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { if (k.next()) return k.getInt(1); }
        } catch (SQLException e) { LOG.severe("[Shop] createItem: " + e.getMessage()); }
        return -1;
    }

    public boolean hasItems() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM shop_items")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) { return false; }
    }

    // ── Volumes (mis à jour à chaque transaction, sans toucher aux prix) ─────

    /**
     * Incrémente uniquement les volumes cumulatifs. Les prix ne changent PAS ici —
     * ils seront recalculés lors de la prochaine régression journalière.
     */
    public void recordBuyVolume(int itemId, int quantity) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shop_items SET total_buy_volume=total_buy_volume+? WHERE id=?")) {
            ps.setInt(1, quantity); ps.setInt(2, itemId);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Shop] recordBuyVolume: " + e.getMessage()); }
    }

    public void recordSellVolume(int itemId, int quantity) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shop_items SET total_sell_volume=total_sell_volume+? WHERE id=?")) {
            ps.setInt(1, quantity); ps.setInt(2, itemId);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Shop] recordSellVolume: " + e.getMessage()); }
    }

    private void updatePrices(int itemId, long nb, long ns) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shop_items SET current_buy_price=?,current_sell_price=? WHERE id=?")) {
            ps.setLong(1,nb); ps.setLong(2,ns); ps.setInt(3,itemId);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Shop] updatePrices: " + e.getMessage()); }
    }

    // ── Historique des prix ──────────────────────────────────────────────────

    /**
     * Calcule le timestamp à utiliser pour le prochain snapshot journalier.
     * Doit être appelé UNE SEULE FOIS avant la boucle sur les items.
     * - Aucun snapshot existant → il y a 6 jours (J-6), pour avoir de la place jusqu'à J=aujourd'hui
     * - Sinon → dernier snapshot + 1 jour (plafonné à maintenant)
     * Ainsi, chaque appel à tick all ajoute un jour distinct sur la courbe 7J.
     */
    public long computeNextDailyTimestamp() {
        long now = System.currentTimeMillis() / 1000L;
        long lastTs = 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(timestamp) FROM shop_price_history WHERE daily=1")) {
            if (rs.next()) lastTs = rs.getLong(1);
        } catch (SQLException ignored) {}
        if (lastTs == 0) return now - 6 * 86400L;
        return Math.min(now, lastTs + 86400L);
    }

    /** Retourne true si au moins un snapshot journalier existe déjà. */
    public boolean hasDailySnapshots() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM shop_price_history WHERE daily=1")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException ignored) { return false; }
    }

    /**
     * Insère un snapshot JOURNALIER (daily=1) avec le timestamp fourni.
     * Appeler computeNextDailyTimestamp() AVANT la boucle sur les items.
     */
    public void snapshotDailyPrice(int itemId, long buyPrice, long sellPrice, long timestamp) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shop_price_history(item_id,timestamp,buy_price,sell_price,daily) VALUES(?,?,?,?,1)")) {
            ps.setInt(1, itemId); ps.setLong(2, timestamp);
            ps.setLong(3, buyPrice); ps.setLong(4, sellPrice);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Shop] snapshotDailyPrice: " + e.getMessage()); }
    }

    /**
     * Retourne l'historique d'achat journalier (daily=1) sur 7 jours — max maxDays points.
     * Un point par jour, trié du plus ancien au plus récent.
     */
    public List<Long> getBuyPriceHistory(int itemId, int maxDays) {
        List<Long> list = new ArrayList<>();
        long sevenDaysAgo = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT buy_price FROM shop_price_history " +
                "WHERE item_id=? AND daily=1 AND timestamp>=? " +
                "ORDER BY timestamp ASC LIMIT ?")) {
            ps.setInt(1, itemId); ps.setLong(2, sevenDaysAgo); ps.setInt(3, maxDays);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(rs.getLong(1)); }
        } catch (SQLException e) { LOG.severe("[Shop] getBuyPriceHistory: " + e.getMessage()); }
        return list;
    }

    /**
     * Retourne l'historique de vente journalier (daily=1) sur 7 jours.
     */
    public List<Long> getSellPriceHistory(int itemId, int maxDays) {
        List<Long> list = new ArrayList<>();
        long sevenDaysAgo = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sell_price FROM shop_price_history " +
                "WHERE item_id=? AND daily=1 AND timestamp>=? " +
                "ORDER BY timestamp ASC LIMIT ?")) {
            ps.setInt(1, itemId); ps.setLong(2, sevenDaysAgo); ps.setInt(3, maxDays);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(rs.getLong(1)); }
        } catch (SQLException e) { LOG.severe("[Shop] getSellPriceHistory: " + e.getMessage()); }
        return list;
    }

    public void purgeOldPriceHistory() {
        long sevenDaysAgo = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM shop_price_history WHERE timestamp<?")) {
            ps.setLong(1, sevenDaysAgo);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("[Shop] Purgé " + n + " entrées historique > 7j");
        } catch (SQLException e) { LOG.severe("[Shop] purgeOldPriceHistory: " + e.getMessage()); }
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    public void logTransaction(String uuid, String name, int itemId, String type, int qty, long priceUnit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shop_transactions(player_uuid,player_name,item_id,type,quantity,price_unit,timestamp)" +
                " VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1,uuid); ps.setString(2,name); ps.setInt(3,itemId);
            ps.setString(4,type); ps.setInt(5,qty); ps.setLong(6,priceUnit);
            ps.setLong(7, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.severe("[Shop] logTransaction: " + e.getMessage()); }
    }

    public void purgeOldTransactions() {
        long sevenDaysAgo = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM shop_transactions WHERE timestamp<?")) {
            ps.setLong(1, sevenDaysAgo);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("[Shop] Purgé " + n + " transactions > 7j");
        } catch (SQLException e) { LOG.severe("[Shop] purgeOldTransactions: " + e.getMessage()); }
    }

    // ── Stats de marché ──────────────────────────────────────────────────────

    /**
     * Top N items les plus achetés dans les 24 dernières heures.
     * Utilise i.id AS id pour éviter l'ambiguïté avec t.item_id dans readItem().
     */
    public List<MarketStatEntry> getTopBoughtLast24h(int limit) {
        List<MarketStatEntry> list = new ArrayList<>();
        long since = System.currentTimeMillis() / 1000L - 86400L;
        String sql = "SELECT i.id AS id, i.display_name, i.minecraft_item, i.meta," +
            " i.current_buy_price, i.current_sell_price, i.category_id," +
            " c.name AS category_name, i.base_buy_price, i.base_sell_price," +
            " i.max_stack, i.frozen, i.floor_price, i.ceil_price," +
            " i.total_buy_volume, i.total_sell_volume," +
            " SUM(t.quantity) AS qty_24h, CAST(AVG(t.price_unit) AS INTEGER) AS avg_price" +
            " FROM shop_transactions t" +
            " JOIN shop_items i ON t.item_id=i.id" +
            " JOIN shop_categories c ON i.category_id=c.id" +
            " WHERE t.type='BUY' AND t.timestamp>=?" +
            " GROUP BY t.item_id ORDER BY qty_24h DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, since); ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new MarketStatEntry(readItem(rs), rs.getLong("qty_24h"), rs.getLong("avg_price")));
            }
        } catch (SQLException e) { LOG.severe("[Shop] getTopBoughtLast24h: " + e.getMessage()); }
        return list;
    }

    /**
     * Top N items les plus vendus dans les 24 dernières heures.
     */
    public List<MarketStatEntry> getTopSoldLast24h(int limit) {
        List<MarketStatEntry> list = new ArrayList<>();
        long since = System.currentTimeMillis() / 1000L - 86400L;
        String sql = "SELECT i.id AS id, i.display_name, i.minecraft_item, i.meta," +
            " i.current_buy_price, i.current_sell_price, i.category_id," +
            " c.name AS category_name, i.base_buy_price, i.base_sell_price," +
            " i.max_stack, i.frozen, i.floor_price, i.ceil_price," +
            " i.total_buy_volume, i.total_sell_volume," +
            " SUM(t.quantity) AS qty_24h, CAST(AVG(t.price_unit) AS INTEGER) AS avg_price" +
            " FROM shop_transactions t" +
            " JOIN shop_items i ON t.item_id=i.id" +
            " JOIN shop_categories c ON i.category_id=c.id" +
            " WHERE t.type='SELL' AND t.timestamp>=?" +
            " GROUP BY t.item_id ORDER BY qty_24h DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, since); ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new MarketStatEntry(readItem(rs), rs.getLong("qty_24h"), rs.getLong("avg_price")));
            }
        } catch (SQLException e) { LOG.severe("[Shop] getTopSoldLast24h: " + e.getMessage()); }
        return list;
    }

    public long getBuyVolumeLast7Days(int itemId) {
        long since = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(quantity),0) FROM shop_transactions WHERE item_id=? AND type='BUY' AND timestamp>=?")) {
            ps.setInt(1, itemId); ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) { LOG.severe("[Shop] getBuyVolumeLast7Days: " + e.getMessage()); }
        return 0;
    }

    public long getSellVolumeLast7Days(int itemId) {
        long since = System.currentTimeMillis() / 1000L - 7 * 86400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(quantity),0) FROM shop_transactions WHERE item_id=? AND type='SELL' AND timestamp>=?")) {
            ps.setInt(1, itemId); ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) { LOG.severe("[Shop] getSellVolumeLast7Days: " + e.getMessage()); }
        return 0;
    }

    // ── Régression journalière ────────────────────────────────────────────────

    /**
     * Calcule les nouveaux prix pour TOUS les items en fonction de l'offre/demande
     * sur les 7 derniers jours. Appelé toutes les 24h (vraies ou simulées).
     *
     * Logique :
     *  - buyVol  >> sellVol  → forte demande → prix monte (jusqu'à +30% max par cycle)
     *  - sellVol >> buyVol   → forte offre   → prix baisse (jusqu'à -30% max par cycle)
     *  - volumes faibles (< seuil)           → régression douce vers le prix de base (8%)
     *  - volumes équilibrés                  → régression douce vers le prix de base
     *
     * Le seuil minimum est de 10 unités sur 7 jours pour qu'un volume soit
     * considéré comme significatif.
     */
    public void applyDailyPriceRegression() {
        List<ShopItem> all = getAllItems();
        long sevenDaysAgo = System.currentTimeMillis() / 1000L - 7 * 86400L;

        try (PreparedStatement psVol = connection.prepareStatement(
                "SELECT " +
                "  COALESCE(SUM(CASE WHEN type='BUY'  THEN quantity END),0) AS bv," +
                "  COALESCE(SUM(CASE WHEN type='SELL' THEN quantity END),0) AS sv " +
                "FROM shop_transactions WHERE item_id=? AND timestamp>=?");
             PreparedStatement psUpd = connection.prepareStatement(
                "UPDATE shop_items SET current_buy_price=?,current_sell_price=? WHERE id=?")) {

            final double REGRESSION   = 0.08;  // retour vers la base si pas d'activité
            final double MAX_MOVE     = 0.30;  // variation max par cycle (30%)
            final long   MIN_VOL      = 10L;   // volume minimum pour affecter les prix

            for (ShopItem item : all) {
                if (item.frozen) continue;

                // Lire les volumes 7 jours depuis les transactions
                psVol.setInt(1, item.id);
                psVol.setLong(2, sevenDaysAgo);
                long buyVol = 0, sellVol = 0;
                try (ResultSet rs = psVol.executeQuery()) {
                    if (rs.next()) { buyVol = rs.getLong("bv"); sellVol = rs.getLong("sv"); }
                }

                long newBuy, newSell;

                if (buyVol < MIN_VOL && sellVol < MIN_VOL) {
                    // Activité insuffisante → régression douce vers le prix de base
                    newBuy  = item.currentBuyPrice  + (long)((item.baseBuyPrice  - item.currentBuyPrice)  * REGRESSION);
                    newSell = item.currentSellPrice + (long)((item.baseSellPrice - item.currentSellPrice) * REGRESSION);
                } else {
                    long total = buyVol + sellVol;
                    // Ratio entre -1 (tout vente) et +1 (tout achat)
                    double pressure = (double)(buyVol - sellVol) / total;
                    // Variation proportionnelle à la pression, plafonnée à MAX_MOVE
                    double move = Math.max(-MAX_MOVE, Math.min(MAX_MOVE, pressure * MAX_MOVE));

                    newBuy  = item.currentBuyPrice  + (long)(item.baseBuyPrice  * move);
                    newSell = item.currentSellPrice + (long)(item.baseSellPrice * move);

                    // Régression partielle vers la base (les prix tendent toujours à revenir)
                    newBuy  += (long)((item.baseBuyPrice  - newBuy)  * REGRESSION * 0.5);
                    newSell += (long)((item.baseSellPrice - newSell) * REGRESSION * 0.5);
                }

                // Respecter les limites floor / ceil
                newBuy  = Math.max(item.floorPrice + 1, Math.min(item.ceilPrice, newBuy));
                newSell = Math.max(item.floorPrice,      Math.min(newBuy - 1,    newSell));

                psUpd.setLong(1, newBuy);
                psUpd.setLong(2, newSell);
                psUpd.setInt(3, item.id);
                psUpd.addBatch();
            }
            psUpd.executeBatch();
        } catch (SQLException e) { LOG.severe("[Shop] applyDailyPriceRegression: " + e.getMessage()); }
    }

    // ── Chargement du catalogue depuis YAML ──────────────────────────────────

    public boolean loadItemsFromConfig() {
        try {
            File configFile = new File(plugin.getDataFolder(), "shop_items.yml");
            if (!configFile.exists()) plugin.saveResource("shop_items.yml", false);

            org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                    new java.io.FileInputStream(configFile), java.nio.charset.StandardCharsets.UTF_8)) {
                config.load(reader);
            }

            if (!config.contains("categories")) {
                LOG.severe("[Shop] Aucune section 'categories' dans shop_items.yml !");
                return false;
            }
            org.bukkit.configuration.ConfigurationSection catsSection = config.getConfigurationSection("categories");
            if (catsSection == null) return false;

            int totalItems = 0, totalCats = 0;
            for (String catKey : catsSection.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection cs = catsSection.getConfigurationSection(catKey);
                if (cs == null) continue;
                String catName = cs.getString("name", catKey);
                String catIcon = cs.getString("icon", "minecraft:chest");
                int catSort    = cs.getInt("sort", 999);
                int catId = createCategory(catName, catIcon, catSort);
                if (catId == -1) continue;
                totalCats++;

                for (String line : cs.getStringList("items")) {
                    String[] p = line.split("\\|");
                    if (p.length != 7) continue;
                    String mcItem = p[1]; int meta = 0;
                    if (mcItem.contains(":")) {
                        String[] mp = mcItem.split(":");
                        mcItem = mp[0];
                        try { meta = Integer.parseInt(mp[1]); } catch (NumberFormatException ignored) {}
                    }
                    try {
                        createItem(catId, p[0], mcItem, meta,
                            Long.parseLong(p[2]), Long.parseLong(p[3]),
                            Integer.parseInt(p[4]), Long.parseLong(p[5]), Long.parseLong(p[6]));
                        totalItems++;
                    } catch (NumberFormatException ignored) {}
                }
            }
            LOG.info("[Shop] Catalogue chargé : " + totalCats + " catégories, " + totalItems + " items.");
            return totalItems > 0;
        } catch (Exception e) {
            LOG.severe("[Shop] Erreur chargement shop_items.yml: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ── Utilitaires admin ────────────────────────────────────────────────────

    public void dropAllShopData() {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM shop_transactions");
            s.executeUpdate("DELETE FROM shop_price_history");
            s.executeUpdate("DELETE FROM shop_items");
            s.executeUpdate("DELETE FROM shop_categories");
        } catch (SQLException e) { LOG.severe("[Shop] dropAllShopData: " + e.getMessage()); }
    }

    public void resetAllPrices() {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("UPDATE shop_items SET current_buy_price=base_buy_price," +
                "current_sell_price=base_sell_price,total_buy_volume=0,total_sell_volume=0");
            s.executeUpdate("DELETE FROM shop_price_history");
        } catch (SQLException e) { LOG.severe("[Shop] resetAllPrices: " + e.getMessage()); }
    }

    public String[] getMarketSummary() {
        int items = 0, frozen = 0;
        long bVol = 0, sVol = 0;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*),SUM(total_buy_volume)," +
                "SUM(total_sell_volume),SUM(CASE WHEN frozen=1 THEN 1 ELSE 0 END) FROM shop_items")) {
            if (rs.next()) { items=rs.getInt(1); bVol=rs.getLong(2); sVol=rs.getLong(3); frozen=rs.getInt(4); }
        } catch (SQLException e) { LOG.severe("[Shop] getMarketSummary: " + e.getMessage()); }
        return new String[]{
            "§6=== État du Marché ===",
            "§7Catégories : §f" + getCategories().size(),
            "§7Items : §f" + items + " §8(dont §c" + frozen + " gelés§8)",
            "§7Volume Achats : §6" + bVol,
            "§7Volume Ventes : §a" + sVol
        };
    }

    // ── Helpers internes ─────────────────────────────────────────────────────

    private ShopItem readItem(ResultSet rs) throws SQLException {
        ShopItem item = new ShopItem();
        item.id               = rs.getInt("id");
        item.categoryId       = rs.getInt("category_id");
        item.displayName      = rs.getString("display_name");
        item.minecraftItem    = rs.getString("minecraft_item");
        item.meta             = rs.getInt("meta");
        item.baseBuyPrice     = rs.getLong("base_buy_price");
        item.baseSellPrice    = rs.getLong("base_sell_price");
        item.currentBuyPrice  = rs.getLong("current_buy_price");
        item.currentSellPrice = rs.getLong("current_sell_price");
        item.maxStack         = rs.getInt("max_stack");
        item.frozen           = rs.getInt("frozen") == 1;
        item.floorPrice       = rs.getLong("floor_price");
        item.ceilPrice        = rs.getLong("ceil_price");
        item.totalBuyVolume   = rs.getLong("total_buy_volume");
        item.totalSellVolume  = rs.getLong("total_sell_volume");
        item.categoryName     = rs.getString("category_name");
        return item;
    }

    // ── Modèles ──────────────────────────────────────────────────────────────

    public static class MarketStatEntry {
        public final ShopItem item;
        public final long quantity24h;
        public final long avgPrice;
        public MarketStatEntry(ShopItem item, long q, long a) { this.item=item; quantity24h=q; avgPrice=a; }
    }

    public static class ShopCategory {
        public final int id;
        public final String name, iconItem;
        public ShopCategory(int id, String name, String iconItem) { this.id=id; this.name=name; this.iconItem=iconItem; }
    }

    public static class ShopItem {
        public int    id, categoryId, meta, maxStack;
        public String displayName, minecraftItem, categoryName;
        public long   baseBuyPrice, baseSellPrice, currentBuyPrice, currentSellPrice;
        public long   floorPrice, ceilPrice, totalBuyVolume, totalSellVolume;
        public boolean frozen;
    }
}
