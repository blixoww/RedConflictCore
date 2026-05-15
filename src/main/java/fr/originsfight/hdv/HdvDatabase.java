package fr.originsfight.hdv;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.CustomPacketServerHandler;import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public class HdvDatabase {
    private static final Logger LOG = Logger.getLogger("HDV-DB");

    public static final long EXPIRY_SECONDS = 604800L;

    public static final int MAX_LISTINGS_PER_PLAYER = 5;

    private final OriginsFightCore plugin;

    private Connection connection;

    public HdvDatabase(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(this.plugin.getDataFolder(), "hdv.db");
            dbFile.getParentFile().mkdirs();
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            this.connection.setAutoCommit(true);
            createTables();
            LOG.info("[HDV] Base de donnconnect: " + dbFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            LOG.severe("[HDV] Impossible de se connecter la base de donn: " + e.getMessage());
            LOG.severe("[HDV] Exception: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        if (this.connection == null)
            return;
        try {
            if (this.connection.isClosed()) {
                this.connection = null;
                return;
            }
            this.connection.close();
            LOG.info("[HDV] Base de données fermée.");
        } catch (SQLException e) {
            LOG.warning("[HDV] Erreur fermeture BDD : " + e.getMessage());
        } finally {
            this.connection = null;
        }
    }

    public Connection getConnection() {
        return this.connection;
    }

    private void createTables() throws SQLException {
        try (Statement stmt = this.connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS hdv_listings (  id INTEGER PRIMARY KEY AUTOINCREMENT,  seller_uuid TEXT NOT NULL,  seller_name TEXT NOT NULL,  item_data   BLOB NOT NULL,  price_per_unit INTEGER NOT NULL,  quantity    INTEGER NOT NULL,  expires_at  INTEGER NOT NULL,  sold        INTEGER NOT NULL DEFAULT 0,  cancelled   INTEGER NOT NULL DEFAULT 0,  pay_pb      INTEGER NOT NULL DEFAULT 0);");
            stmt.execute("CREATE TABLE IF NOT EXISTS hdv_earnings (  uuid        TEXT PRIMARY KEY,  player_name TEXT NOT NULL,  amount      INTEGER NOT NULL DEFAULT 0);");
            stmt.execute("CREATE TABLE IF NOT EXISTS hdv_transactions (  id           INTEGER PRIMARY KEY AUTOINCREMENT,  ts           INTEGER NOT NULL,  buyer_name   TEXT NOT NULL,  seller_name  TEXT NOT NULL,  item_name    TEXT NOT NULL,  quantity     INTEGER NOT NULL,  price        INTEGER NOT NULL);");
        }
        // Migration : ajouter la colonne cancelled si elle n'existe pas encore (base existante)
        try (Statement stmt = this.connection.createStatement()) {
            stmt.execute("ALTER TABLE hdv_listings ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {}
        // Migration : ajouter la colonne pay_pb
        try (Statement stmt = this.connection.createStatement()) {
            stmt.execute("ALTER TABLE hdv_listings ADD COLUMN pay_pb INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {}
    }

    public void logTransaction(String buyerName, String sellerName, String itemName, int quantity, long price) {
        try (PreparedStatement ps = this.connection.prepareStatement("INSERT INTO hdv_transactions (ts, buyer_name, seller_name, item_name, quantity, price) VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis() / 1000L);
            ps.setString(2, buyerName);
            ps.setString(3, sellerName);
            ps.setString(4, itemName);
            ps.setInt(5, quantity);
            ps.setLong(6, price);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("[HDV] logTransaction error: " + e.getMessage());
        }
    }

    public List<String[]> getTransactions(String playerName, int limit) {
        List<String[]> result = (List)new ArrayList<>();
        try {
            String sql = (playerName == null) ? "SELECT ts, buyer_name, seller_name, item_name, quantity, price FROM hdv_transactions ORDER BY id DESC LIMIT ?" : "SELECT ts, buyer_name, seller_name, item_name, quantity, price FROM hdv_transactions WHERE buyer_name=? OR seller_name=? ORDER BY id DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                if (playerName == null) {
                    ps.setInt(1, limit);
                } else {
                    ps.setString(1, playerName);
                    ps.setString(2, playerName);
                    ps.setInt(3, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new String[] { String.valueOf(rs.getLong("ts")), rs
                                .getString("buyer_name"), rs
                                .getString("seller_name"), rs
                                .getString("item_name"),
                                String.valueOf(rs.getInt("quantity")),
                                String.valueOf(rs.getLong("price")) });
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[HDV] getTransactions error: " + e.getMessage());
        }
        return result;
    }

    public int createListing(String sellerUuid, String sellerName, ItemStack item, long totalPrice, int quantity, boolean payPB) {
        try {
            int active = countActiveListings(sellerUuid);
            if (active >= 5)
                return -2;
            long expiresAt = System.currentTimeMillis() / 1000L + 604800L;
            byte[] itemData = serializeItemStatic(item);
            if (itemData == null)
                return -1;
            String sql = "INSERT INTO hdv_listings (seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at, pay_pb) VALUES (?,?,?,?,?,?,?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql, 1)) {
                ps.setString(1, sellerUuid);
                ps.setString(2, sellerName);
                ps.setBytes(3, itemData);
                ps.setLong(4, totalPrice);
                ps.setInt(5, quantity);
                ps.setLong(6, expiresAt);
                ps.setInt(7, payPB ? 1 : 0);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next())
                        return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] createListing error: " + e.getMessage());
        }
        return -1;
    }

    /** Compat : ancienne signature sans payPB → false par défaut. */
    public int createListing(String sellerUuid, String sellerName, ItemStack item, long totalPrice, int quantity) {
        return createListing(sellerUuid, sellerName, item, totalPrice, quantity, false);
    }

    public List<HdvListing> getActiveListings(int page, int pageSize, String filter) {
        List<HdvListing> result = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        String sql = "SELECT id, seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at, pay_pb FROM hdv_listings WHERE sold=0 AND expires_at > ? ORDER BY id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, pageSize);
            ps.setInt(3, page * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemStack item = deserializeItem(rs.getBytes("item_data"));
                    boolean match = true;
                    if (filter != null && !filter.isEmpty()) {
                        String f = filter.toLowerCase().trim();
                        // Nom de l'item (displayName ou type)
                        String iName = "";
                        if (item != null) {
                            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                                iName = item.getItemMeta().getDisplayName();
                            } else {
                                iName = item.getType().name();
                            }
                        }
                        String sName = rs.getString("seller_name");
                        // Recherche étendue : inclure les enchantements du livre
                        String searchStr = iName.toLowerCase() + " " + sName.toLowerCase();
                        if (item != null && EnchantUtils.isEnchantedBook(item)) {
                            searchStr += " " + EnchantUtils.getSearchString(item);
                        }
                        if (!searchStr.contains(f)) match = false;
                    }
                    if (match)
                        result.add(new HdvListing(rs
                                .getInt("id"), rs
                                .getString("seller_uuid"), rs
                                .getString("seller_name"), item, rs

                                .getLong("price_per_unit"), rs
                                .getInt("quantity"), rs
                                .getLong("expires_at")));
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getActiveListings error: " + e.getMessage());
        }
        return result;
    }

    public HdvListing getListingById(int id) {
        try {
            String sql = "SELECT id, seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at, pay_pb FROM hdv_listings WHERE id=? AND sold=0";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next())
                        return readListing(rs);
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getListingById error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Force l'expiration immédiate d'une annonce active (admin).
     * @return true si une ligne a été mise à jour, false si introuvable / déjà sold/cancelled.
     */
    public boolean forceExpireListing(int id) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "UPDATE hdv_listings SET expires_at=1 WHERE id=? AND sold=0 AND cancelled=0")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warning("[HDV] forceExpireListing error: " + e.getMessage());
            return false;
        }
    }

    public boolean buyListingNoEarnings(int listingId, int qtyToBuy) {
        try {
            this.connection.setAutoCommit(false);
            HdvListing listing = getListingById(listingId);
            if (listing == null || listing.getQuantity() < qtyToBuy) {
                this.connection.rollback();
                return false;
            }
            int newQty = listing.getQuantity() - qtyToBuy;
            if (newQty <= 0) {
                try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET quantity=0, sold=1 WHERE id=?")) {
                    ps.setInt(1, listingId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET quantity=? WHERE id=?")) {
                    ps.setInt(1, newQty);
                    ps.setInt(2, listingId);
                    ps.executeUpdate();
                }
            }
            this.connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                this.connection.rollback();
            } catch (SQLException sQLException) {}
            LOG.severe("[HDV] buyListingNoEarnings error: " + e.getMessage());
            return false;
        } finally {
            try {
                this.connection.setAutoCommit(true);
            } catch (SQLException sQLException) {}
        }
    }

    public boolean buyListing(int listingId, int qtyToBuy) {
        try {
            this.connection.setAutoCommit(false);
            HdvListing listing = getListingById(listingId);
            if (listing == null || listing.getQuantity() < qtyToBuy) {
                this.connection.rollback();
                return false;
            }
            int newQty = listing.getQuantity() - qtyToBuy;
            if (newQty <= 0) {
                try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET quantity=0, sold=1 WHERE id=?")) {
                    ps.setInt(1, listingId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET quantity=? WHERE id=?")) {
                    ps.setInt(1, newQty);
                    ps.setInt(2, listingId);
                    ps.executeUpdate();
                }
            }
            long earned = listing.getPricePerUnit() * qtyToBuy;
            addEarnings(listing.getSellerUuid(), listing.getSellerName(), earned);
            this.connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                this.connection.rollback();
            } catch (SQLException sQLException) {}
            LOG.severe("[HDV] buyListing error: " + e.getMessage());
            return false;
        } finally {
            try {
                this.connection.setAutoCommit(true);
            } catch (SQLException sQLException) {}
        }
    }

    public HdvListing cancelListing(int listingId, String sellerUuid) {
        try {
            HdvListing listing = getListingById(listingId);
            if (listing == null || !sellerUuid.equals(listing.getSellerUuid()))
                return null;
            // cancelled=1 : retiré par le vendeur (pas une vente, donc pas affiché comme "vendu")
            try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET sold=1, cancelled=1 WHERE id=?")) {
                ps.setInt(1, listingId);
                ps.executeUpdate();
            }
            return listing;
        } catch (SQLException e) {
            LOG.severe("[HDV] cancelListing error: " + e.getMessage());
            return null;
        }
    }

    public int countActiveListings(String sellerUuid) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            try (PreparedStatement ps = this.connection.prepareStatement("SELECT COUNT(*) FROM hdv_listings WHERE seller_uuid=? AND sold=0 AND expires_at > ?")) {
                ps.setString(1, sellerUuid);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next())
                        return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] countActiveListings error: " + e.getMessage());
        }
        return 0;
    }

    public void addEarnings(String uuid, String name, long amount) {
        try {
            // Utiliser INSERT OR REPLACE compatible SQLite pour éviter les erreurs de syntaxe ON CONFLICT
            String upsertSql = "INSERT OR REPLACE INTO hdv_earnings (uuid, player_name, amount) " +
                    "VALUES (?, ?, COALESCE((SELECT amount FROM hdv_earnings WHERE uuid = ?), 0) + ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(upsertSql)) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, uuid);
                ps.setLong(4, amount);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] addEarnings error: " + e.getMessage());
        }
    }

    public long getPendingEarnings(String uuid) {
        try (PreparedStatement ps = this.connection.prepareStatement("SELECT amount FROM hdv_earnings WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getPendingEarnings error: " + e.getMessage());
        }
        return 0L;
    }

    public long collectEarnings(String uuid) {
        try {
            long amount = getPendingEarnings(uuid);
            if (amount <= 0L)
                return 0L;
            // Remet le solde en attente à 0
            try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_earnings SET amount=0 WHERE uuid=?")) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            }
            // Masque les annonces vendues : cancelled=1 pour qu'elles n'apparaissent plus dans "mes annonces"
            try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET cancelled=1 WHERE seller_uuid=? AND sold=1 AND cancelled=0")) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            }
            return amount;
        } catch (SQLException e) {
            LOG.severe("[HDV] collectEarnings error: " + e.getMessage());
            return 0L;
        }
    }

    public static byte[] serializeItemStatic(ItemStack item) {
        if (item == null)
            return new byte[0];
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
            Class<?> craftItemStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsItemStackCls = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> nbtTagCompoundCls = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
            Class<?> nbtCompressedStreamTools = Class.forName("net.minecraft.server." + v + ".NBTCompressedStreamTools");
            Object nmsStack = craftItemStackCls.getMethod("asNMSCopy", new Class[] { ItemStack.class }).invoke(null, new Object[] { item });
            if (nmsStack != null) {
                Object nbtTag = nbtTagCompoundCls.newInstance();
                nmsItemStackCls.getMethod("save", new Class[] { nbtTagCompoundCls }).invoke(nmsStack, new Object[] { nbtTag });
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                nbtCompressedStreamTools.getMethod("a", new Class[] { nbtTagCompoundCls, OutputStream.class }).invoke(null, new Object[] { nbtTag, baos });
                byte[] data = baos.toByteArray();
                return data;
            }
        } catch (Exception e) {
            LOG.warning("[HDV] serializeItemStatic NMS failed (" + e.getMessage() + "), fallback Bukkit.");
        }
        return serializeItemBukkit(item);
    }

    private static byte[] serializeItemBukkit(ItemStack item) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(new byte[] { 66, 85, 75, 75 });
            BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
            boos.writeObject(item);
            boos.close();
            return baos.toByteArray();
        } catch (Exception e) {
            LOG.severe("[HDV] serializeItemBukkit error: " + e.getMessage());
            return new byte[0];
        }
    }

    public static ItemStack deserializeItem(byte[] data) {
        if (data == null || data.length == 0)
            return null;
        if (data.length > 4 && data[0] == 66 && data[1] == 85 && data[2] == 75 && data[3] == 75) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                bais.skip(4L);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
                Object obj = bois.readObject();
                if (obj instanceof ItemStack)
                    return (ItemStack)obj;
            } catch (Exception e) {
                LOG.warning("[HDV] Read BUKK format failed: " + e.getMessage());
            }
        } else if (data.length > 2 && data[0] == -84 && data[1] == -19) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
                Object obj = bois.readObject();
                if (obj instanceof ItemStack)
                    return (ItemStack)obj;
            } catch (Exception exception) {}
        }
        try {
            return deserializeItemNms(data);
        } catch (Exception e) {
            LOG.severe("[HDV] deserializeItem all formats failed for " + data.length + " bytes.");
            return null;
        }
    }

    private static ItemStack deserializeItemNms(byte[] data) throws Exception {
        Object nbtTag;
        String v = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
        Class<?> craftItemStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
        Class<?> nmsItemStackCls = Class.forName("net.minecraft.server." + v + ".ItemStack");
        Class<?> nbtTagCompoundCls = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
        Class<?> nbtCompressedStreamTools = Class.forName("net.minecraft.server." + v + ".NBTCompressedStreamTools");
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try {
            nbtTag = nbtCompressedStreamTools.getMethod("a", new Class[] { InputStream.class }).invoke(null, new Object[] { bais });
        } catch (NoSuchMethodException ns) {
            nbtTag = nbtCompressedStreamTools.getMethod("a", new Class[] { DataInputStream.class }).invoke(null, new Object[] { new DataInputStream(bais) });
        }
        if (nbtTag != null) {
            Object nmsStack = nmsItemStackCls.getMethod("createStack", new Class[] { nbtTagCompoundCls }).invoke(null, new Object[] { nbtTag });
            if (nmsStack != null)
                return (ItemStack)craftItemStackCls.getMethod("asBukkitCopy", new Class[] { nmsItemStackCls }).invoke(null, new Object[] { nmsStack });
        }
        return null;
    }

    public int clearListingsForPlayer(String sellerUuid) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET sold=1, cancelled=1 WHERE seller_uuid=? AND sold=0 AND expires_at > ?")) {
                ps.setString(1, sellerUuid);
                ps.setLong(2, now);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] clearListingsForPlayer error: " + e.getMessage());
            return 0;
        }
    }

    /** Retourne les IDs de toutes les annonces actives (pour l'auto-complétion admin). */
    public List<Integer> getActiveListingIds() {
        List<Integer> ids = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT id FROM hdv_listings WHERE sold=0 AND cancelled=0 AND expires_at > ? ORDER BY id")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            LOG.warning("[HDV] getActiveListingIds error: " + e.getMessage());
        }
        return ids;
    }

    /** Retourne les annonces expirées non vendues (sold=0, expires_at <= now) d'un joueur,
     *  pour qu'il puisse les récupérer depuis le menu "Mes annonces". */
    public List<HdvListing> getExpiredListingsForPlayer(String sellerUuid) {
        List<HdvListing> result = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        String sql = "SELECT id, seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at, pay_pb FROM hdv_listings WHERE seller_uuid=? AND sold=0 AND cancelled=0 AND expires_at <= ?"
                + " ORDER BY id DESC LIMIT 10";
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setString(1, sellerUuid);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HdvListing l = readListing(rs);
                    if (l != null) result.add(l);
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getExpiredListingsForPlayer error: " + e.getMessage());
        }
        return result;
    }

    /** Retourne les annonces actives (sold=0) d'un joueur */
    public List<HdvListing> getActiveListingsForPlayer(String sellerUuid) {
        List<HdvListing> result = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        String sql = "SELECT id, seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at, pay_pb FROM hdv_listings WHERE seller_uuid=? AND sold=0 AND expires_at > ? ORDER BY id DESC";
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setString(1, sellerUuid);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HdvListing l = readListing(rs);
                    if (l != null) result.add(l);
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getActiveListingsForPlayer error: " + e.getMessage());
        }
        return result;
    }

    /** Retourne les annonces vendues (sold=1, cancelled=0) récentes d'un joueur.
     *  Les annonces retirées par le vendeur (cancelled=1) sont exclues.
     *  Limite aux 10 dernières. */
    public List<HdvListing> getSoldListingsForPlayer(String sellerUuid) {
        List<HdvListing> result = new ArrayList<>();
        // cancelled=0 : vraiment vendues par un acheteur (pas retirées par le vendeur)
        String sql = "SELECT id, seller_uuid, seller_name, item_data, price_per_unit, quantity, expires_at FROM hdv_listings WHERE seller_uuid=? AND sold=1 AND cancelled=0 ORDER BY id DESC LIMIT 10";
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setString(1, sellerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        HdvListing l = new HdvListing();
                        l.setId(rs.getInt("id"));
                        l.setSellerUuid(rs.getString("seller_uuid"));
                        l.setSellerName(rs.getString("seller_name"));
                        l.setItem(deserializeItem(rs.getBytes("item_data")));
                        l.setTotalPrice(rs.getLong("price_per_unit"));
                        l.setQuantity(rs.getInt("quantity"));
                        l.setExpiresAt(rs.getLong("expires_at"));
                        l.setSold(true);
                        result.add(l);
                    } catch (Exception e) {
                        LOG.warning("[HDV] getSoldListingsForPlayer row error: " + e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            LOG.severe("[HDV] getSoldListingsForPlayer error: " + e.getMessage());
        }
        return result;
    }

    private HdvListing readListing(ResultSet rs) {
        try {
            HdvListing listing = new HdvListing();
            listing.setId(rs.getInt("id"));
            listing.setSellerUuid(rs.getString("seller_uuid"));
            listing.setSellerName(rs.getString("seller_name"));
            byte[] itemBytes = rs.getBytes("item_data");
            if (itemBytes == null || itemBytes.length == 0) {
                invalidateListing(listing.getId());
                return null;
            }

            ItemStack item = deserializeItem(itemBytes);
            if (item == null) {
                invalidateListing(listing.getId());
                return null;
            }

            // Important: pour les items custom MCP, getTypeId() peut être 0 alors que l'item est valide.
            // On valide via la résolution robuste côté packet handler.
            int resolvedId = CustomPacketServerHandler.getNmsItemId(item);
            if (resolvedId <= 0 && item.getType() == Material.AIR) {
                invalidateListing(listing.getId());
                return null;
            }

            listing.setItem(item);
            listing.setTotalPrice(rs.getLong("price_per_unit"));
            listing.setQuantity(rs.getInt("quantity"));
            listing.setExpiresAt(rs.getLong("expires_at"));
            try { listing.setPayPB(rs.getInt("pay_pb") == 1); } catch (SQLException ignored) {}
            return listing;
        } catch (SQLException e) {
            return null;
        }
    }

    private void invalidateListing(int id) {
        try (PreparedStatement ps = this.connection.prepareStatement("UPDATE hdv_listings SET sold=1 WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
            LOG.info("[HDV] Annonce #" + id + " invalid(donncorrompues)");
        } catch (SQLException e) {
            LOG.warning("[HDV] invalidateListing error: " + e.getMessage());
        }
    }
}
