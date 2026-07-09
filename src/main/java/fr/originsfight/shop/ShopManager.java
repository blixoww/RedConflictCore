package fr.originsfight.shop;

import fr.originsfight.RedConflictCore;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.ring.RingEffects;
import fr.originsfight.shop.ShopDatabase.ShopCategory;
import fr.originsfight.shop.ShopDatabase.ShopItem;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class ShopManager {
    private static final Logger LOG = Logger.getLogger("Shop");
    private static final String CHANNEL_S2C = "CUSTOM:SHOP_S2C";
    private static final int MAX_HISTORY = 20;

    // Packet IDs S2C
    private static final int SHOP_CATEGORIES_RESPONSE = 0x40;
    private static final int SHOP_ITEMS_RESPONSE      = 0x41;
    private static final int SHOP_TRANSACTION_RESULT   = 0x42;
    private static final int SHOP_MARKET_STATS         = 0x43;
    private static final int SHOP_OPEN                 = 0x44;
    private static final int SHOP_EVENT_UPDATE         = 0x45;

    private static ShopManager instance;

    private final RedConflictCore plugin;
    private final ShopDatabase database;
    private Economy economy;
    private int priceSnapshotTaskId = -1;
    private int dailyRegressionTaskId = -1;
    private long nextRegressionTime = 0;
    private ShopEventManager eventManager;

    public ShopEventManager getEventManager() { return eventManager; }
    public void setEventManager(ShopEventManager m) { this.eventManager = m; }

    private long effBuy(ShopItem it) {
        return eventManager != null ? eventManager.effectiveBuyPrice(it) : it.currentBuyPrice;
    }
    private long effSell(ShopItem it) {
        return eventManager != null ? eventManager.effectiveSellPrice(it) : it.currentSellPrice;
    }

    // Prix effectifs tenant compte du Sceau du marchand (shop uniquement) :
    // -5% à l'achat, +5% à la vente. Le NBT de l'anneau est lu côté serveur.
    private long effBuy(Player player, ShopItem it) {
        long base = effBuy(it);
        if (fr.originsfight.ring.RingEffects.hasRing(player, RingEffects.NECKLACE_OF_MERCHANT)) {
            return Math.round(base * 0.95D);
        }
        return base;
    }
    private long effSell(Player player, ShopItem it) {
        long base = effSell(it);
        if (fr.originsfight.ring.RingEffects.hasRing(player, fr.originsfight.ring.RingEffects.NECKLACE_OF_MERCHANT)) {
            return Math.round(base * 1.05D);
        }
        return base;
    }

    public static ShopManager getInstance() { return instance; }

    public ShopManager(RedConflictCore plugin) {
        this.plugin = plugin;
        this.database = new ShopDatabase(plugin, plugin.getCoreDatabase());
        instance = this;
    }

    public boolean enable() {
        if (!database.connect()) {
            LOG.severe("[Shop] Impossible d'initialiser la base de données !");
            return false;
        }
        setupEconomy();

        if (!database.hasItems()) {
            if (!database.loadItemsFromConfig()) {
                LOG.severe("[Shop] Impossible de charger les items depuis shop_items.yml !");
                return false;
            }
        }

        startDailyRegressionTask();
        LOG.info("[Shop] Shop initialisé.");
        return true;
    }

    public void disable() {
        if (priceSnapshotTaskId != -1) {
            Bukkit.getScheduler().cancelTask(priceSnapshotTaskId);
        }
        if (dailyRegressionTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dailyRegressionTaskId);
        }
        database.disconnect();
    }

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    public ShopDatabase getDatabase() { return database; }

    // ── Régression journalière (toutes les 24h) ─────────────────────────────

    private void startDailyRegressionTask() {
        // 1728000 ticks = 24 heures (20 ticks/sec * 3600 * 24)
        long interval = 1728000L;
        nextRegressionTime = System.currentTimeMillis() + (interval / 20L * 1000L);

        dailyRegressionTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            boolean isFirst = !database.hasDailySnapshots();
            if (isFirst) {
                long tsBefor = System.currentTimeMillis() / 1000L - 86400L;
                for (ShopItem item : database.getAllItems()) {
                    database.snapshotDailyPrice(item.id, item.currentBuyPrice, item.currentSellPrice, tsBefor);
                }
            }
            database.applyDailyPriceRegression();
            long dailyTs = database.computeNextDailyTimestamp();
            for (ShopItem item : database.getAllItems()) {
                database.snapshotDailyPrice(item.id, item.currentBuyPrice, item.currentSellPrice, dailyTs);
            }
            database.purgeOldPriceHistory();
            database.purgeOldTransactions();
            nextRegressionTime = System.currentTimeMillis() + (interval / 20L * 1000L);
            LOG.info("[Shop] Régression journalière terminée.");
        }, interval, interval).getTaskId();
    }

    public long getNextRegressionTime() {
        return nextRegressionTime;
    }

    public void simulateDailyRegression() { simulateDailyRegression(null); }

    public void simulateDailyRegression(Runnable onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ShopItem> all = database.getAllItems();
            boolean isFirst = !database.hasDailySnapshots();

            // Premier tick : snapshot J-1 avec les prix ACTUELS (avant régression)
            // pour avoir immédiatement 2 points et afficher la variation dès le 1er tick
            if (isFirst) {
                long tsBefor = System.currentTimeMillis() / 1000L - 86400L; // exactement J-1
                for (ShopItem item : all) {
                    database.snapshotDailyPrice(item.id, item.currentBuyPrice, item.currentSellPrice, tsBefor);
                }
            }

            // Appliquer la régression (prix mis à jour en base)
            database.applyDailyPriceRegression();

            // Snapshot J0 avec les prix post-régression
            long dailyTs = database.computeNextDailyTimestamp();
            for (ShopItem item : database.getAllItems()) {
                database.snapshotDailyPrice(item.id, item.currentBuyPrice, item.currentSellPrice, dailyTs);
            }

            database.purgeOldPriceHistory();
            database.purgeOldTransactions();

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                    sendItems(online, -1);
                    sendMarketStats(online);
                }
                if (eventManager != null) eventManager.tickForSimulation();
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void resetAndReloadShop() {
        database.dropAllShopData();
        database.loadItemsFromConfig();
    }

    // ── Ouvrir le shop ──────────────────────────────────────────────────────

    public void openShop(Player player) {
        byte[] pkt = PacketBuilder.create(SHOP_OPEN).build();
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pkt);
    }

    // ── Envoyer les categories ──────────────────────────────────────────────

    public void sendCategories(Player player) {
        List<ShopCategory> cats = database.getCategories();
        PacketBuilder pb = PacketBuilder.create(SHOP_CATEGORIES_RESPONSE);
        pb.writeVarInt(cats.size());
        for (ShopCategory cat : cats) {
            pb.writeVarInt(cat.id);
            pb.writeString(cat.name);
            pb.writeString(cat.iconItem);
        }
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
        // Envoyer aussi les market stats + état des events boursiers
        sendMarketStats(player);
        sendEventState(player);
    }

    /** Envoie au client la liste des events boursiers actifs (krach/inflation/aubaine). */
    public void sendEventState(Player player) {
        PacketBuilder pb = PacketBuilder.create(SHOP_EVENT_UPDATE);
        List<ShopDatabase.ShopEventRow> events = (eventManager != null)
                ? eventManager.getActiveEvents()
                : java.util.Collections.<ShopDatabase.ShopEventRow>emptyList();
        long now = System.currentTimeMillis() / 1000L;
        pb.writeVarInt(events.size());
        for (ShopDatabase.ShopEventRow e : events) {
            pb.writeString(e.type == null ? "" : e.type);
            pb.writeLong(Math.max(0, e.endTs - now));  // secondes restantes
            pb.writeDouble(e.multiplierBuy);
            pb.writeDouble(e.multiplierSell);
            // CSV item ids (vide = global)
            pb.writeString(e.itemIdsCsv == null ? "" : e.itemIdsCsv);
            pb.writeString(e.announcement == null ? "" : e.announcement);
        }
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
    }

    /** Diffuse l'état des events à tous les joueurs en ligne. */
    public void broadcastEventState() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendEventState(p);
            // Pousser les prix mis à jour aussi (rafraîchissement du shop ouvert)
            sendItems(p, -1);
        }
    }

    // ── Envoyer les items d'une categorie ───────────────────────────────────

    public void sendItems(Player player, int categoryId) {
        List<ShopItem> items;
        if (categoryId == -1) {
            items = database.getAllItems();
        } else {
            items = database.getItemsByCategory(categoryId);
        }

        // action=0 => clear & add
        sendItemList(player, items, 0);
    }

    private void sendItemList(Player player, List<ShopItem> items, int action) {
        // Chunking pour ne pas depasser la taille max de paquet (32KB)
        int MAX_CHUNK = 20; // max items par paquet
        int start = 0;
        boolean first = true;

        while (start < items.size() || first) {
            int end = Math.min(start + MAX_CHUNK, items.size());
            List<ShopItem> chunk = items.subList(start, end);

            PacketBuilder pb = PacketBuilder.create(SHOP_ITEMS_RESPONSE);
            // action: 0 = clear & add (premier chunk), 1 = append (chunks suivants)
            pb.writeVarInt(first ? action : 1);
            pb.writeVarInt(chunk.size());

            for (ShopItem item : chunk) {
                writeShopItem(pb, player, item);
            }

            player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
            first = false;
            start = end;
            if (start >= items.size()) break;
        }

        // Si la liste est vide, envoyer un paquet vide
        if (items.isEmpty()) {
            PacketBuilder pb = PacketBuilder.create(SHOP_ITEMS_RESPONSE);
            pb.writeVarInt(0); // action = clear
            pb.writeVarInt(0); // count = 0
            player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
        }
    }

    // Items dont le meta=0 doit aussi être encodé pour distinguer les variantes
    private static final java.util.Set<String> META_SENSITIVE_ITEMS = new java.util.HashSet<>(java.util.Arrays.asList(
        "wool", "stained_glass", "stained_glass_pane", "stained_hardened_clay",
        "carpet", "dye", "planks", "log", "log2", "sapling", "sand", "stone",
        "stonebrick", "stone_slab", "wooden_slab", "red_flower", "skull",
        "red_sandstone", "sandstone", "quartz_block", "dirt", "fish",
        "cooked_fish", "golden_apple", "sponge", "prismarine", "banner"
    ));

    private void writeShopItem(PacketBuilder pb, Player player, ShopItem item) {
        pb.writeVarInt(item.id);
        pb.writeString(item.displayName);
        // Toujours inclure le meta pour les items qui en ont besoin,
        // même quand meta=0, pour distinguer les variantes (laine blanche vs laine rouge, etc.)
        String mcItemWithMeta = item.minecraftItem;
        if (item.meta > 0 || META_SENSITIVE_ITEMS.contains(item.minecraftItem)) {
            mcItemWithMeta = item.minecraftItem + ":" + item.meta;
        }
        pb.writeString(mcItemWithMeta);
        pb.writeLong(effBuy(player, item));
        pb.writeLong(effSell(player, item));
        pb.writeVarInt(item.maxStack);
        pb.writeString(item.categoryName != null ? item.categoryName : "");
        pb.writeBoolean(item.frozen);
        pb.writeLong(item.floorPrice);
        pb.writeLong(item.ceilPrice);
        pb.writeString(generateAsciiChart(item.id));

        // Historique achat
        List<Long> buyHist = database.getBuyPriceHistory(item.id, MAX_HISTORY);
        pb.writeVarInt(buyHist.size());
        for (long price : buyHist) pb.writeLong(price);

        // Historique vente
        List<Long> sellHist = database.getSellPriceHistory(item.id, MAX_HISTORY);
        pb.writeVarInt(sellHist.size());
        for (long price : sellHist) pb.writeLong(price);

        // Volumes cumules
        pb.writeLong(item.totalBuyVolume);
        pb.writeLong(item.totalSellVolume);
    }

    // ── Achat ────────────────────────────────────────────────────────────────

    public void handleBuy(Player player, int itemId, int quantity) {
        if (economy == null) {
            sendTransactionResult(player, false, "Economie indisponible.", 0);
            return;
        }
        if (quantity <= 0) {
            sendTransactionResult(player, false, "Quantite invalide.", getBalance(player));
            return;
        }

        ShopItem item = database.getItemById(itemId);
        if (item == null) {
            sendTransactionResult(player, false, "Item introuvable.", getBalance(player));
            return;
        }
        if (item.frozen) {
            sendTransactionResult(player, false, "Cet item est gele (indisponible).", getBalance(player));
            return;
        }

        long unitBuy = effBuy(player, item);
        long totalCost = unitBuy * quantity;
        long balance = getBalance(player);

        if (balance < totalCost) {
            sendTransactionResult(player, false, "Fonds insuffisants.", balance);
            return;
        }

        // Retirer l'argent
        if (!economy.withdrawPlayer(player, totalCost).transactionSuccess()) {
            sendTransactionResult(player, false, "Erreur lors du retrait.", getBalance(player));
            return;
        }

        // Donner les items
        giveItems(player, item.minecraftItem, item.meta, quantity);

        // Enregistrer le volume cumulatif (prix mis à jour uniquement toutes les 24h)
        database.recordBuyVolume(itemId, quantity);

        // Logger la transaction (avec le prix réellement payé, event inclus)
        database.logTransaction(
            player.getUniqueId().toString(), player.getName(),
            itemId, "BUY", quantity, unitBuy
        );


        long newBalance = getBalance(player);
        String msg = "Achat de " + quantity + "x " + item.displayName + " pour " +
                     formatPrice(totalCost) + " $";
        sendTransactionResult(player, true, msg, newBalance);

        // Mettre à jour les top achats/ventes instantanément
        sendMarketStats(player);
    }

    // ── Vente ────────────────────────────────────────────────────────────────

    public void handleSell(Player player, int itemId, int quantity) {
        if (economy == null) {
            sendTransactionResult(player, false, "Economie indisponible.", 0);
            return;
        }

        ShopItem item = database.getItemById(itemId);
        if (item == null) {
            sendTransactionResult(player, false, "Item introuvable.", getBalance(player));
            return;
        }
        if (item.frozen) {
            sendTransactionResult(player, false, "Cet item est gele (indisponible).", getBalance(player));
            return;
        }
        if (item.currentSellPrice <= 0) {
            sendTransactionResult(player, false, "Cet item ne peut pas etre vendu.", getBalance(player));
            return;
        }

        // Compter les items du joueur
        int available = countItems(player, item.minecraftItem, item.meta);

        if (quantity == -1) {
            // Vendre tout
            quantity = available;
        }

        if (quantity <= 0 || available < quantity) {
            sendTransactionResult(player, false, "Vous n'avez pas assez d'items.", getBalance(player));
            return;
        }

        // Retirer les items
        removeItems(player, item.minecraftItem, item.meta, quantity);

        long unitSell = effSell(player, item);
        long totalEarned = unitSell * quantity;

        // Donner l'argent
        economy.depositPlayer(player, totalEarned);

        // Enregistrer le volume cumulatif (prix mis à jour uniquement toutes les 24h)
        database.recordSellVolume(itemId, quantity);

        // Logger la transaction (avec le prix réellement reçu, event inclus)
        database.logTransaction(
            player.getUniqueId().toString(), player.getName(),
            itemId, "SELL", quantity, unitSell
        );


        long newBalance = getBalance(player);
        String msg = "Vente de " + quantity + "x " + item.displayName + " pour " +
                     formatPrice(totalEarned) + " $";
        sendTransactionResult(player, true, msg, newBalance);

        // Mettre à jour les top achats/ventes instantanément
        sendMarketStats(player);
    }

    // ── Vente automatique de tout l'inventaire ───────────────────────────────

    /**
     * Vend automatiquement tous les items de l'inventaire du joueur qui ont un
     * prix de vente actif dans le shop (non gelé, prix > 0).
     * Affiche un résumé dans le chat, ou un message si rien n'a pu être vendu.
     */
    public void handleSellAll(Player player) {
        if (economy == null) {
            player.sendMessage("§c[Shop] Économie indisponible.");
            return;
        }

        List<ShopDatabase.ShopItem> allItems = database.getAllItems();

        long totalEarned = 0L;
        int totalQty = 0;
        int linesSold = 0; // nbre de types d'items différents vendus

        for (ShopDatabase.ShopItem item : allItems) {
            if (item.frozen || item.currentSellPrice <= 0) continue;

            int available = countItems(player, item.minecraftItem, item.meta);
            if (available <= 0) continue;

            // Retirer les items
            removeItems(player, item.minecraftItem, item.meta, available);

            long unitSell = effSell(player, item);
            long earned = unitSell * available;
            totalEarned += earned;
            totalQty += available;
            linesSold++;

            // Enregistrer volume + transaction
            database.recordSellVolume(item.id, available);
            database.logTransaction(
                    player.getUniqueId().toString(), player.getName(),
                    item.id, "SELL", available, unitSell
            );
        }

        if (totalQty == 0) {
            player.sendMessage("§6[Shop] §7Aucun item vendable trouvé dans votre inventaire.");
            return;
        }

        // Déposer l'argent
        economy.depositPlayer(player, totalEarned);
        player.updateInventory();

        long newBalance = getBalance(player);
        player.sendMessage("§6[Shop] §aVente automatique terminée !");
        player.sendMessage("§7  §f" + totalQty + " §7item" + (totalQty > 1 ? "s" : "")
                + " §8(" + linesSold + " type" + (linesSold > 1 ? "s" : "") + ")"
                + " §7vendus pour §a" + formatPrice(totalEarned) + " $");
        player.sendMessage("§7  Solde : §f" + formatPrice(newBalance) + " $");

        // Mettre à jour les stats de marché
        sendMarketStats(player);
    }

    // ── Detail d'un item ─────────────────────────────────────────────────────

    public void sendItemDetail(Player player, int itemId) {
        ShopItem item = database.getItemById(itemId);
        if (item == null) return;

        // Envoyer comme un ITEMS_RESPONSE avec action=0 et un seul item
        PacketBuilder pb = PacketBuilder.create(SHOP_ITEMS_RESPONSE);
        pb.writeVarInt(0); // action = clear & add
        pb.writeVarInt(1); // count = 1
        writeShopItem(pb, player, item);
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
    }

    // ── Market Stats ─────────────────────────────────────────────────────────

    public void sendMarketStats(Player player) {
        List<ShopDatabase.MarketStatEntry> topBought = database.getTopBoughtLast24h(10);
        List<ShopDatabase.MarketStatEntry> topSold   = database.getTopSoldLast24h(10);

        PacketBuilder pb = PacketBuilder.create(SHOP_MARKET_STATS);

        // Top bought (24h)
        // Format client : id (VarInt), displayName (String), mcItem (String),
        //                  buyPrice (long), sellPrice (long), volume (long), avgPrice (long)
        pb.writeVarInt(topBought.size());
        for (ShopDatabase.MarketStatEntry e : topBought) {
            String mcItem = (e.item.meta > 0 || META_SENSITIVE_ITEMS.contains(e.item.minecraftItem))
                    ? e.item.minecraftItem + ":" + e.item.meta
                    : e.item.minecraftItem;
            pb.writeVarInt(e.item.id);
            pb.writeString(e.item.displayName);
            pb.writeString(mcItem);
            pb.writeLong(effBuy(e.item));
            pb.writeLong(effSell(e.item));
            pb.writeLong(e.quantity24h);
            pb.writeLong(e.avgPrice);  // prix moyen historique de la transaction (24h)
        }

        // Top sold (24h)
        pb.writeVarInt(topSold.size());
        for (ShopDatabase.MarketStatEntry e : topSold) {
            String mcItem = (e.item.meta > 0 || META_SENSITIVE_ITEMS.contains(e.item.minecraftItem))
                    ? e.item.minecraftItem + ":" + e.item.meta
                    : e.item.minecraftItem;
            pb.writeVarInt(e.item.id);
            pb.writeString(e.item.displayName);
            pb.writeString(mcItem);
            pb.writeLong(effBuy(e.item));
            pb.writeLong(effSell(e.item));
            pb.writeLong(e.quantity24h);
            pb.writeLong(e.avgPrice);  // prix moyen historique de la transaction (24h)
        }

        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pb.build());
    }

    // ── Packet Transaction Result ────────────────────────────────────────────

    private void sendTransactionResult(Player player, boolean success, String message, long balance) {
        byte[] pkt = PacketBuilder.create(SHOP_TRANSACTION_RESULT)
                .writeBoolean(success)
                .writeString(message)
                .writeLong(balance)
                .build();
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, pkt);
    }

    // ── Inventaire ───────────────────────────────────────────────────────────

    /**
     * Extrait le meta depuis le nom de l'item (ex: "coal:1" -> 1, "stone" -> defaultMeta).
     */
    private int resolveEffectiveMeta(String minecraftItem, int defaultMeta) {
        if (minecraftItem == null) return defaultMeta;
        String name = minecraftItem;
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.contains(":")) {
            String[] parts = name.split(":");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException ignored) {}
        }
        return defaultMeta;
    }

    @SuppressWarnings("deprecation")
    private void giveItems(Player player, String minecraftItem, int meta, int quantity) {
        Material mat = resolveMaterial(minecraftItem);
        int effectiveMeta = resolveEffectiveMeta(minecraftItem, meta);
        if (mat == null || mat == Material.AIR) {
            LOG.warning("[Shop] Cannot resolve material: " + minecraftItem);
            return;
        }

        int remaining = quantity;
        while (remaining > 0) {
            int give = Math.min(remaining, mat.getMaxStackSize());
            ItemStack stack = new ItemStack(mat, give, (short) effectiveMeta);
            player.getInventory().addItem(stack).values().forEach(
                left -> player.getWorld().dropItemNaturally(player.getLocation(), left)
            );
            remaining -= give;
        }
        player.updateInventory();
    }

    @SuppressWarnings("deprecation")
    private int countItems(Player player, String minecraftItem, int meta) {
        Material mat = resolveMaterial(minecraftItem);
        if (mat == null) return 0;
        int effectiveMeta = resolveEffectiveMeta(minecraftItem, meta);

        int count = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && s.getType() == mat && s.getDurability() == (short) effectiveMeta) {
                count += s.getAmount();
            }
        }
        return count;
    }

    @SuppressWarnings("deprecation")
    private void removeItems(Player player, String minecraftItem, int meta, int amount) {
        Material mat = resolveMaterial(minecraftItem);
        if (mat == null) return;
        int effectiveMeta = resolveEffectiveMeta(minecraftItem, meta);

        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s != null && s.getType() == mat && s.getDurability() == (short) effectiveMeta) {
                if (s.getAmount() <= remaining) {
                    remaining -= s.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - remaining);
                    remaining = 0;
                }
            }
        }
        player.updateInventory();
    }

    /**
     * Resout un nom d'item Minecraft (ex: "minecraft:diamond" ou "coal:1") en Material Bukkit.
     * Le suffixe :meta est ignoré pour la résolution du Material.
     */
    @SuppressWarnings("deprecation")
    private Material resolveMaterial(String minecraftItem) {
        if (minecraftItem == null) return null;
        String name = minecraftItem;
        // Retirer le prefixe "minecraft:"
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        // Retirer le suffixe :meta (ex: "coal:1" -> "coal")
        if (name.contains(":")) {
            String[] parts = name.split(":");
            // Vérifier si la partie après : est un nombre (meta)
            try {
                Integer.parseInt(parts[parts.length - 1]);
                name = name.substring(0, name.lastIndexOf(':'));
            } catch (NumberFormatException ignored) {
                // Ce n'est pas un meta, c'est un namespace (ex: "minecraft:diamond")
            }
        }

        // Essayer directement par nom Bukkit (upper case)
        Material mat = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        if (mat != null) return mat;

        // Essayer par ID numerique
        try {
            int id = Integer.parseInt(name);
            mat = Material.getMaterial(id);
            if (mat != null) return mat;
        } catch (NumberFormatException ignored) {}

        // Table de correspondance pour les noms courants MC -> Bukkit
        switch (name.toLowerCase(Locale.ROOT)) {
            case "log":             return Material.LOG;
            case "log2":            return Material.LOG_2;
            case "planks":          return Material.WOOD;
            case "cobblestone":     return Material.COBBLESTONE;
            case "stone":           return Material.STONE;
            case "iron_ingot":      return Material.IRON_INGOT;
            case "gold_ingot":      return Material.GOLD_INGOT;
            case "diamond":         return Material.DIAMOND;
            case "emerald":         return Material.EMERALD;
            case "coal":            return Material.COAL;
            case "redstone":        return Material.REDSTONE;
            case "lapis_lazuli":    return Material.INK_SACK; // meta=4
            case "quartz":          return Material.QUARTZ;
            case "iron_ore":        return Material.IRON_ORE;
            case "gold_ore":        return Material.GOLD_ORE;
            case "diamond_ore":     return Material.DIAMOND_ORE;
            case "coal_ore":        return Material.COAL_ORE;
            case "emerald_ore":     return Material.EMERALD_ORE;
            case "lapis_ore":       return Material.LAPIS_ORE;
            case "redstone_ore":    return Material.REDSTONE_ORE;
            case "obsidian":        return Material.OBSIDIAN;
            case "sand":            return Material.SAND;
            case "gravel":          return Material.GRAVEL;
            case "dirt":            return Material.DIRT;
            case "grass":           return Material.GRASS;
            case "glass":           return Material.GLASS;
            case "brick":           return Material.CLAY_BRICK;
            case "clay_ball":       return Material.CLAY_BALL;
            case "clay":            return Material.CLAY;
            case "glowstone_dust":  return Material.GLOWSTONE_DUST;
            case "glowstone":       return Material.GLOWSTONE;
            case "ender_pearl":     return Material.ENDER_PEARL;
            case "blaze_rod":       return Material.BLAZE_ROD;
            case "ghast_tear":      return Material.GHAST_TEAR;
            case "slime_ball":      return Material.SLIME_BALL;
            case "leather":         return Material.LEATHER;
            case "string":          return Material.STRING;
            case "feather":         return Material.FEATHER;
            case "bone":            return Material.BONE;
            case "gunpowder":       return Material.SULPHUR;
            case "spider_eye":      return Material.SPIDER_EYE;
            case "rotten_flesh":    return Material.ROTTEN_FLESH;
            case "wheat":           return Material.WHEAT;
            case "bread":           return Material.BREAD;
            case "apple":           return Material.APPLE;
            case "golden_apple":    return Material.GOLDEN_APPLE;
            case "porkchop":        return Material.PORK;
            case "cooked_porkchop": return Material.GRILLED_PORK;
            case "beef":            return Material.RAW_BEEF;
            case "cooked_beef":     return Material.COOKED_BEEF;
            case "chicken":         return Material.RAW_CHICKEN;
            case "cooked_chicken":  return Material.COOKED_CHICKEN;
            case "fish":            return Material.RAW_FISH;
            case "cooked_fish":     return Material.COOKED_FISH;
            case "melon":           return Material.MELON;
            case "pumpkin":         return Material.PUMPKIN;
            case "cactus":          return Material.CACTUS;
            case "sugar_cane":      return Material.SUGAR_CANE;
            case "egg":             return Material.EGG;
            case "nether_wart":     return Material.NETHER_STALK;
            case "experience_bottle": return Material.EXP_BOTTLE;
            case "tnt":             return Material.TNT;
            case "arrow":           return Material.ARROW;
            case "bow":             return Material.BOW;
            case "fishing_rod":     return Material.FISHING_ROD;
            case "book":            return Material.BOOK;
            case "paper":           return Material.PAPER;
            case "nether_star":     return Material.NETHER_STAR;
            case "prismarine_shard": return Material.PRISMARINE_SHARD;
            case "prismarine_crystals": return Material.PRISMARINE_CRYSTALS;
            case "oak_sapling":     return Material.SAPLING;
            case "netherrack":      return Material.NETHERRACK;
            case "soul_sand":       return Material.SOUL_SAND;
            case "end_stone":       return Material.ENDER_STONE;
            case "wool":            return Material.WOOL;
            case "dye":             return Material.INK_SACK;
            case "iron_block":      return Material.IRON_BLOCK;
            case "gold_block":      return Material.GOLD_BLOCK;
            case "diamond_block":   return Material.DIAMOND_BLOCK;
            case "emerald_block":   return Material.EMERALD_BLOCK;
            case "lapis_block":     return Material.LAPIS_BLOCK;
            case "redstone_block":  return Material.REDSTONE_BLOCK;
            case "coal_block":      return Material.COAL_BLOCK;
            case "chest":           return Material.CHEST;
            case "hopper":          return Material.HOPPER;
            case "dropper":         return Material.DROPPER;
            case "dispenser":       return Material.DISPENSER;
            case "piston":          return Material.PISTON_BASE;
            case "sticky_piston":   return Material.PISTON_STICKY_BASE;
            case "rail":            return Material.RAILS;
            case "golden_rail":     return Material.POWERED_RAIL;
            case "detector_rail":   return Material.DETECTOR_RAIL;
            case "activator_rail":  return Material.ACTIVATOR_RAIL;
            case "oak_fence":       return Material.FENCE;
            case "nether_brick_fence": return Material.NETHER_FENCE;
            case "iron_bars":       return Material.IRON_FENCE;
            case "glass_pane":      return Material.THIN_GLASS;
            case "torch":           return Material.TORCH;
            case "ladder":          return Material.LADDER;
            case "bucket":          return Material.BUCKET;
            case "water_bucket":    return Material.WATER_BUCKET;
            case "lava_bucket":     return Material.LAVA_BUCKET;
            case "milk_bucket":     return Material.MILK_BUCKET;
            case "saddle":          return Material.SADDLE;
            case "name_tag":        return Material.NAME_TAG;
            case "lead":            return Material.LEASH;
            // Nouveaux matériaux du catalogue étendu
            case "stonebrick":      return Material.SMOOTH_BRICK;
            case "brick_block":     return Material.BRICK;
            case "nether_brick":    return Material.NETHER_BRICK;
            case "sandstone":       return Material.SANDSTONE;
            case "red_sandstone":   return Material.RED_SANDSTONE;
            case "quartz_block":    return Material.QUARTZ_BLOCK;
            case "prismarine":      return Material.PRISMARINE;
            case "sea_lantern":     return Material.SEA_LANTERN;
            case "sponge":          return Material.SPONGE;
            case "stained_hardened_clay": return Material.STAINED_CLAY;
            case "hardened_clay":   return Material.HARD_CLAY;
            case "stained_glass":   return Material.STAINED_GLASS;
            case "stained_glass_pane": return Material.STAINED_GLASS_PANE;
            case "carpet":          return Material.CARPET;
            case "snow":            return Material.SNOW_BLOCK;
            case "ice":             return Material.ICE;
            case "packed_ice":      return Material.PACKED_ICE;
            case "anvil":           return Material.ANVIL;
            case "melon_block":     return Material.MELON_BLOCK;
            case "lit_pumpkin":     return Material.JACK_O_LANTERN;
            case "fence":           return Material.FENCE;
            case "fence_gate":      return Material.FENCE_GATE;
            case "wooden_door":     return Material.WOOD_DOOR;
            case "trapdoor":        return Material.TRAP_DOOR;
            case "sign":            return Material.SIGN;
            case "oak_stairs":      return Material.WOOD_STAIRS;
            case "spruce_stairs":   return Material.SPRUCE_WOOD_STAIRS;
            case "birch_stairs":    return Material.BIRCH_WOOD_STAIRS;
            case "jungle_stairs":   return Material.JUNGLE_WOOD_STAIRS;
            case "acacia_stairs":   return Material.ACACIA_STAIRS;
            case "dark_oak_stairs": return Material.DARK_OAK_STAIRS;
            case "wooden_slab":     return Material.WOOD_STEP;
            case "crafting_table":  return Material.WORKBENCH;
            case "sapling":         return Material.SAPLING;
            case "reeds":           return Material.SUGAR_CANE;
            case "sugar":           return Material.SUGAR;
            case "red_mushroom":    return Material.RED_MUSHROOM;
            case "brown_mushroom":  return Material.BROWN_MUSHROOM;
            case "waterlily":       return Material.WATER_LILY;
            case "vine":            return Material.VINE;
            case "mycelium":        return Material.MYCEL;
            case "snowball":        return Material.SNOW_BALL;
            case "wheat_seeds":     return Material.SEEDS;
            case "melon_seeds":     return Material.MELON_SEEDS;
            case "pumpkin_seeds":   return Material.PUMPKIN_SEEDS;
            case "baked_potato":    return Material.BAKED_POTATO;
            case "potato":          return Material.POTATO_ITEM;
            case "carrot":          return Material.CARROT_ITEM;
            case "golden_carrot":   return Material.GOLDEN_CARROT;
            case "cookie":          return Material.COOKIE;
            case "pumpkin_pie":     return Material.PUMPKIN_PIE;
            case "mushroom_stew":   return Material.MUSHROOM_SOUP;
            case "rabbit":          return Material.RABBIT;
            case "cooked_rabbit":   return Material.COOKED_RABBIT;
            case "rabbit_stew":     return Material.RABBIT_STEW;
            case "rabbit_hide":     return Material.RABBIT_HIDE;
            case "rabbit_foot":     return Material.RABBIT_FOOT;
            case "mutton":          return Material.MUTTON;
            case "cooked_mutton":   return Material.COOKED_MUTTON;
            case "cake":            return Material.CAKE;
            case "speckled_melon":  return Material.SPECKLED_MELON;
            case "fermented_spider_eye": return Material.FERMENTED_SPIDER_EYE;
            case "blaze_powder":    return Material.BLAZE_POWDER;
            case "magma_cream":     return Material.MAGMA_CREAM;
            case "netherbrick":     return Material.NETHER_BRICK_ITEM;
            case "beacon":          return Material.BEACON;
            // Armures
            case "leather_helmet":     return Material.LEATHER_HELMET;
            case "leather_chestplate": return Material.LEATHER_CHESTPLATE;
            case "leather_leggings":   return Material.LEATHER_LEGGINGS;
            case "leather_boots":      return Material.LEATHER_BOOTS;
            case "chainmail_helmet":   return Material.CHAINMAIL_HELMET;
            case "chainmail_chestplate": return Material.CHAINMAIL_CHESTPLATE;
            case "chainmail_leggings": return Material.CHAINMAIL_LEGGINGS;
            case "chainmail_boots":    return Material.CHAINMAIL_BOOTS;
            case "iron_helmet":        return Material.IRON_HELMET;
            case "iron_chestplate":    return Material.IRON_CHESTPLATE;
            case "iron_leggings":      return Material.IRON_LEGGINGS;
            case "iron_boots":         return Material.IRON_BOOTS;
            case "golden_helmet":      return Material.GOLD_HELMET;
            case "golden_chestplate":  return Material.GOLD_CHESTPLATE;
            case "golden_leggings":    return Material.GOLD_LEGGINGS;
            case "golden_boots":       return Material.GOLD_BOOTS;
            case "diamond_helmet":     return Material.DIAMOND_HELMET;
            case "diamond_chestplate": return Material.DIAMOND_CHESTPLATE;
            case "diamond_leggings":   return Material.DIAMOND_LEGGINGS;
            case "diamond_boots":      return Material.DIAMOND_BOOTS;
            // Épées
            case "wooden_sword":       return Material.WOOD_SWORD;
            case "stone_sword":        return Material.STONE_SWORD;
            case "iron_sword":         return Material.IRON_SWORD;
            case "golden_sword":       return Material.GOLD_SWORD;
            case "diamond_sword":      return Material.DIAMOND_SWORD;
            // Outils
            case "wooden_pickaxe":     return Material.WOOD_PICKAXE;
            case "stone_pickaxe":      return Material.STONE_PICKAXE;
            case "iron_pickaxe":       return Material.IRON_PICKAXE;
            case "golden_pickaxe":     return Material.GOLD_PICKAXE;
            case "diamond_pickaxe":    return Material.DIAMOND_PICKAXE;
            case "wooden_axe":         return Material.WOOD_AXE;
            case "stone_axe":          return Material.STONE_AXE;
            case "iron_axe":           return Material.IRON_AXE;
            case "golden_axe":         return Material.GOLD_AXE;
            case "diamond_axe":        return Material.DIAMOND_AXE;
            case "wooden_shovel":      return Material.WOOD_SPADE;
            case "stone_shovel":       return Material.STONE_SPADE;
            case "iron_shovel":        return Material.IRON_SPADE;
            case "golden_shovel":      return Material.GOLD_SPADE;
            case "diamond_shovel":     return Material.DIAMOND_SPADE;
            case "wooden_hoe":         return Material.WOOD_HOE;
            case "iron_hoe":           return Material.IRON_HOE;
            case "diamond_hoe":        return Material.DIAMOND_HOE;
            case "shears":             return Material.SHEARS;
            case "flint_and_steel":    return Material.FLINT_AND_STEEL;
            case "flint":              return Material.FLINT;
            case "compass":            return Material.COMPASS;
            case "clock":              return Material.WATCH;
            // Redstone supplémentaire
            case "redstone_torch":     return Material.REDSTONE_TORCH_ON;
            case "repeater":           return Material.DIODE;
            case "comparator":         return Material.REDSTONE_COMPARATOR;
            case "redstone_lamp":      return Material.REDSTONE_LAMP_OFF;
            case "stone_pressure_plate": return Material.STONE_PLATE;
            case "wooden_pressure_plate": return Material.WOOD_PLATE;
            case "stone_button":       return Material.STONE_BUTTON;
            case "wooden_button":      return Material.WOOD_BUTTON;
            case "lever":              return Material.LEVER;
            case "tripwire_hook":      return Material.TRIPWIRE_HOOK;
            case "noteblock":          return Material.NOTE_BLOCK;
            case "daylight_detector":  return Material.DAYLIGHT_DETECTOR;
            // Décoration
            case "yellow_flower":      return Material.YELLOW_FLOWER;
            case "red_flower":         return Material.RED_ROSE;
            case "painting":           return Material.PAINTING;
            case "item_frame":         return Material.ITEM_FRAME;
            case "flower_pot":         return Material.FLOWER_POT_ITEM;
            case "skull":              return Material.SKULL_ITEM;
            case "banner":             return Material.BANNER;
            case "writable_book":      return Material.BOOK_AND_QUILL;
            case "stone_brick_stairs": return Material.SMOOTH_STAIRS;
            case "brick_stairs":       return Material.BRICK_STAIRS;
            case "sandstone_stairs":   return Material.SANDSTONE_STAIRS;
            case "quartz_stairs":      return Material.QUARTZ_STAIRS;
            case "stone_slab":         return Material.STEP;
            // Divers
            case "ender_eye":          return Material.EYE_OF_ENDER;
            case "enchanting_table":   return Material.ENCHANTMENT_TABLE;
            case "brewing_stand":      return Material.BREWING_STAND_ITEM;
            case "cauldron":           return Material.CAULDRON_ITEM;
            case "firework_charge":    return Material.FIREWORK_CHARGE;
            case "fireworks":          return Material.FIREWORK;
            case "minecart":           return Material.MINECART;
            case "chest_minecart":     return Material.STORAGE_MINECART;
            case "tnt_minecart":       return Material.EXPLOSIVE_MINECART;
            case "hopper_minecart":    return Material.HOPPER_MINECART;
            case "boat":              return Material.BOAT;
            case "iron_door":         return Material.IRON_DOOR;
            case "iron_trapdoor":     return Material.IRON_TRAPDOOR;
            case "quartz_ore":        return Material.QUARTZ_ORE;
            default:                return null;
        }
    }

    // ── Chart ASCII ──────────────────────────────────────────────────────────

    private String generateAsciiChart(int itemId) {
        // On prend les snapshots des 7 derniers jours, max 28 points
        List<Long> history = database.getBuyPriceHistory(itemId, 28);
        if (history.size() < 2) return "";

        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : history) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min == max) {
            // Légère variation artificielle pour afficher quelque chose
            max = min + 1;
        }

        int height = 5;
        // Séparateur de lignes : pipe | (évite les problèmes d'encodage LF/CRLF)
        StringBuilder sb = new StringBuilder();
        for (int row = height - 1; row >= 0; row--) {
            for (long v : history) {
                int level = (int) ((v - min) * (height - 1) / (max - min));
                sb.append(level >= row ? '#' : '.');
            }
            if (row > 0) sb.append('|');  // séparateur de ligne (pas \n)
        }
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long getBalance(Player player) {
        return economy != null ? (long) economy.getBalance(player) : 0L;
    }

    private String formatPrice(long centimes) {
        if (centimes >= 100_000_000L) return String.format("%.1fM", centimes / 100_000_000.0);
        if (centimes >= 100_000L)     return String.format("%.1fK", centimes / 100_000.0);
        return String.format("%.2f", centimes / 100.0);
    }
}
