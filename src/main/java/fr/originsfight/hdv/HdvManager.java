package fr.originsfight.hdv;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.CustomPacketServerHandler;
import fr.originsfight.packets.PacketBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

public class HdvManager {
    private static final Logger LOG = Logger.getLogger("HDV");

    private static HdvManager instance;

    private final OriginsFightCore plugin;

    private final HdvDatabase database;

    private EconomyProvider economy;

    private HdvEconomy internalEconomy;

    public static HdvManager getInstance() {
        return instance;
    }

    public HdvManager(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.database = new HdvDatabase(plugin);
        instance = this;
    }

    public boolean enable() {
        if (!this.database.connect()) {
            LOG.severe("[HDV] Impossible d'initialiser la base de donn!");
            return false;
        }
        if (setupVaultEconomy()) {
            LOG.info("[HDV] Vault trouve et utilise comme economy.");
        } else {
            this.internalEconomy = new HdvEconomy(this.database.getConnection());
            this.economy = this.internalEconomy;
            LOG.info("[HDV] Vault non trouvutilisation de l'interne (SQLite).");
        }
        LOG.info("[HDV] Activation reussie.");
        return true;
    }

    private boolean setupVaultEconomy() {
        if (this.plugin.getServer().getPluginManager().getPlugin("Vault") == null)
            return false;
        RegisteredServiceProvider<?> rsp = this.plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null)
            return false;
        Economy vaultEco = (Economy)rsp.getProvider();
        if (vaultEco != null) {
            this.economy = new VaultAdapter(vaultEco);
            return true;
        }
        return false;
    }

    private static class VaultAdapter implements EconomyProvider {
        private final Economy vault;

        public VaultAdapter(Economy v) {
            this.vault = v;
        }

        public long getBalance(Player p) {
            return (long)this.vault.getBalance((OfflinePlayer)p);
        }

        public boolean withdraw(Player p, long amount) {
            return this.vault.withdrawPlayer((OfflinePlayer)p, amount).transactionSuccess();
        }

        public void deposit(Player p, long amount) {
            this.vault.depositPlayer((OfflinePlayer)p, amount);
        }
    }

    public HdvEconomy getInternalEconomy() {
        return this.internalEconomy;
    }

    public void disable() {
        this.database.disconnect();
    }

    public void setEconomyProvider(EconomyProvider p) {
        this.economy = p;
    }

    public EconomyProvider getEconomyProvider() {
        return this.economy;
    }

    public HdvDatabase getDatabase() {
        return this.database;
    }

    /** Envoie le packet HDV_OPEN (0x25) pour ouvrir l'interface côté client. */
    public void sendOpen(Player player) {
        byte[] pkt = PacketBuilder.create(0x25).build();
        player.sendPluginMessage((Plugin) this.plugin, "CUSTOM:HDV_S2C", pkt);
    }

    /** Force l'expiration d'une annonce (commande admin ou packet 0x17). */
    public void handleForceExpire(Player staff, int listingId) {
        boolean ok = this.database.forceExpireListing(listingId);
        if (ok) {
            staff.sendMessage(ChatColor.GOLD + "[HDV] " + ChatColor.GREEN
                    + "Annonce #" + listingId + " expirée avec succès. Le vendeur peut la récupérer.");
        } else {
            staff.sendMessage(ChatColor.GOLD + "[HDV] " + ChatColor.RED
                    + "Annonce #" + listingId + " introuvable ou déjà vendue/annulée.");
        }
    }

    public void sendPlayerBalance(Player player) {
        long balance = (this.economy != null) ? this.economy.getBalance(player) : 0L;
        byte[] pkt = PacketBuilder.create(80).writeLong(balance).build();
        player.sendPluginMessage((Plugin)this.plugin, "CUSTOM:PDATA_S2C", pkt);
    }

    private byte[] serializeItemForNetwork(ItemStack item) {
        if (item == null)
            return new byte[] { -1, -1 };
        try {
            String v = nmsVersion();
            Class<?> craftItemStackCls = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsItemStackCls = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> unpooledCls = Class.forName("io.netty.buffer.Unpooled");
            Class<?> byteBufCls = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> pdsCls = Class.forName("net.minecraft.server." + v + ".PacketDataSerializer");
            Object nmsStack = craftItemStackCls.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            if (nmsStack == null) {
                LOG.warning("[HDV] serializeItemForNetwork: asNMSCopy retourne null pour " + item.getType() + " id=" + CustomPacketServerHandler.getNmsItemId(item));
                return new byte[] { -1, -1 };
            }
            Object byteBuf = unpooledCls.getMethod("buffer").invoke(null);
            Object pds = pdsCls.getConstructor(byteBufCls).newInstance(byteBuf);
            pdsCls.getMethod("a", nmsItemStackCls).invoke(pds, nmsStack);
            int len = (int) byteBufCls.getMethod("readableBytes").invoke(byteBuf);
            byte[] result = new byte[len];
            byteBufCls.getMethod("getBytes", int.class, byte[].class).invoke(byteBuf, 0, result);
            return result;
        } catch (Exception e) {
            LOG.warning("[HDV] serializeItemForNetwork error: " + e.getMessage());
            return new byte[] { -1, -1 };
        }
    }

    public void sendListings(Player player, int page, String filter) {
        List<HdvListing> listings = this.database.getActiveListings(page, 48, filter);
        List<byte[]> serialized = new ArrayList<>();
        for (HdvListing l : listings) {
            if (l.getItem() == null) {
                LOG.warning("[HDV] Listing #" + l.getId() + " ignoritem null");
                continue;
            }
            try {
                serialized.add(serializeListing(l));
            } catch (Exception e) {
                LOG.warning("[HDV] Erreur listing #" + l.getId() + ": " + e.getMessage());
            }
        }
        int MAX_CHUNK = 30000;
        if (serialized.isEmpty()) {
            player.sendPluginMessage((Plugin)this.plugin, "CUSTOM:HDV_S2C",
                    PacketBuilder.create(32).writeVarInt(0).build());
        } else {
            int start = 0;
            while (start < serialized.size()) {
                int end = start, size = 4;
                while (end < serialized.size()) {
                    int n = ((byte[])serialized.get(end)).length;
                    if (size + n > 30000 && end > start)
                        break;
                    size += n;
                    end++;
                }
                PacketBuilder pb = PacketBuilder.create(32).writeVarInt(end - start);
                for (int i = start; i < end; ) {
                    pb.writeBytes(serialized.get(i));
                    i++;
                }
                byte[] packet = pb.build();
                player.sendPluginMessage((Plugin)this.plugin, "CUSTOM:HDV_S2C", packet);
                start = end;
            }
        }
        sendPlayerBalance(player);
    }

    private byte[] serializeListing(HdvListing l) {
        PacketBuilder pb = new PacketBuilder();
        pb.writeVarInt(l.getId());
        pb.writeString((l.getSellerName() != null) ? l.getSellerName() : "");
        pb.writeBytes(serializeItemForNetwork(l.getItem()));
        pb.writeLong(l.getTotalPrice());
        pb.writeVarInt(l.getQuantity());
        pb.writeLong(l.getExpiresAt());
        return pb.buildRaw();
    }

    /** Envoie au joueur ses annonces actives + expirées + vendues (packet ID 0x24). */
    public void sendMyListings(Player player) {
        String uuid = player.getUniqueId().toString();
        List<HdvListing> active  = this.database.getActiveListingsForPlayer(uuid);
        List<HdvListing> expired = this.database.getExpiredListingsForPlayer(uuid);
        List<HdvListing> sold    = this.database.getSoldListingsForPlayer(uuid);
        long pendingEarnings    = this.database.getPendingEarnings(uuid);

        List<byte[]> serialized = new ArrayList<>();
        for (HdvListing l : active) {
            if (l.getItem() == null) continue;
            try {
                PacketBuilder pb = new PacketBuilder();
                pb.writeVarInt(l.getId());
                pb.writeString((l.getSellerName() != null) ? l.getSellerName() : "");
                pb.writeBytes(serializeItemForNetwork(l.getItem()));
                pb.writeLong(l.getTotalPrice());
                pb.writeVarInt(l.getQuantity());
                pb.writeLong(l.getExpiresAt());
                pb.writeBoolean(false); // not sold
                serialized.add(pb.buildRaw());
            } catch (Exception e) {
                LOG.warning("[HDV] sendMyListings active error: " + e.getMessage());
            }
        }
        for (HdvListing l : expired) {
            if (l.getItem() == null) continue;
            try {
                PacketBuilder pb = new PacketBuilder();
                pb.writeVarInt(l.getId());
                pb.writeString((l.getSellerName() != null) ? l.getSellerName() : "");
                pb.writeBytes(serializeItemForNetwork(l.getItem()));
                pb.writeLong(l.getTotalPrice());
                pb.writeVarInt(l.getQuantity());
                pb.writeLong(l.getExpiresAt()); // expiresAt in the past → client isExpired() = true
                pb.writeBoolean(false); // not sold, just expired
                serialized.add(pb.buildRaw());
            } catch (Exception e) {
                LOG.warning("[HDV] sendMyListings expired error: " + e.getMessage());
            }
        }
        for (HdvListing l : sold) {
            try {
                ItemStack item = l.getItem();
                PacketBuilder pb = new PacketBuilder();
                pb.writeVarInt(l.getId());
                pb.writeString((l.getSellerName() != null) ? l.getSellerName() : "");
                if (item != null) {
                    pb.writeBytes(serializeItemForNetwork(item));
                } else {
                    pb.writeBytes(new byte[]{-1, -1});
                }
                pb.writeLong(l.getTotalPrice());
                pb.writeVarInt(l.getQuantity());
                pb.writeLong(l.getExpiresAt());
                pb.writeBoolean(true); // sold
                serialized.add(pb.buildRaw());
            } catch (Exception e) {
                LOG.warning("[HDV] sendMyListings sold error: " + e.getMessage());
            }
        }

        // Packet ID 0x24 = HDV_MY_LISTINGS_RESPONSE
        if (serialized.isEmpty()) {
            byte[] pkt = PacketBuilder.create(0x24)
                    .writeVarInt(0)
                    .writeLong(pendingEarnings)
                    .build();
            player.sendPluginMessage((Plugin)this.plugin, "CUSTOM:HDV_S2C", pkt);
        } else {
            int start = 0;
            boolean first = true;
            while (start < serialized.size()) {
                int end = start, size = first ? 12 : 4; // header (4 varint count + 8 long)
                while (end < serialized.size()) {
                    int n = serialized.get(end).length;
                    if (size + n > 28000 && end > start) break;
                    size += n;
                    end++;
                }
                PacketBuilder pb = PacketBuilder.create(0x24).writeVarInt(end - start);
                if (first) { pb.writeLong(pendingEarnings); first = false; }
                else { pb.writeLong(-1L); } // pas re-envoyé si multi-chunk
                for (int i = start; i < end; i++) pb.writeBytes(serialized.get(i));
                player.sendPluginMessage((Plugin)this.plugin, "CUSTOM:HDV_S2C", pb.build());
                start = end;
            }
        }
        sendPlayerBalance(player);
    }

    private String nmsVersion() {
        return Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
    }

    public void handleBuy(Player player, int listingId) {
        if (this.economy == null)
            return;
        HdvListing listing = this.database.getListingById(listingId);
        if (listing == null) {
            HdvServerHandler.sendActionResult(player, false, "Annonce introuvable ou vendue.");
            return;
        }
        if (listing.getSellerUuid().equals(player.getUniqueId().toString())) {
            HdvServerHandler.sendActionResult(player, false, "Vous ne pouvez pas acheter votre propre item !");
            return;
        }
        long price = listing.getTotalPrice();
        if (price < 0L)
            return;
        if (this.economy.getBalance(player) < price) {
            HdvServerHandler.sendActionResult(player, false, "Fonds insuffisants.");
            return;
        }
        boolean success = this.database.buyListing(listingId, listing.getQuantity());
        if (success) {
            this.economy.withdraw(player, price);
            giveItem(player, listing.getItem(), listing.getQuantity());
            String itemName = (listing.getItem().hasItemMeta() && listing.getItem().getItemMeta().hasDisplayName())
                    ? listing.getItem().getItemMeta().getDisplayName()
                    : listing.getItem().getType().name();
            this.database.logTransaction(player.getName(), listing.getSellerName(), itemName, listing.getQuantity(), price);
            HdvServerHandler.sendActionResult(player, true, "Achat effectue !");
            // Notifier le vendeur s'il est en ligne
            Player seller = Bukkit.getPlayerExact(listing.getSellerName());
            if (seller != null && seller.isOnline()) {
                byte[] notif = PacketBuilder.create(0x26)
                        .writeString(itemName.length() > 64 ? itemName.substring(0, 64) : itemName)
                        .writeVarInt(listing.getQuantity())
                        .writeLong(price)
                        .build();
                seller.sendPluginMessage((Plugin) this.plugin, "CUSTOM:HDV_S2C", notif);
            }
            sendListings(player, 0, "");
        } else {
            HdvServerHandler.sendActionResult(player, false, "Achat echoue (deja vendu ?).");
        }
    }

    public void handlePostOffer(Player player, ItemStack item, long totalPrice, int quantity) {
        if (this.economy == null) {
            player.sendMessage("§cHDV indisponible.");
            return;
        }
        // Vérifier via NMS ID (support items moddés dont getType()==AIR côté Bukkit)
        if (item == null || CustomPacketServerHandler.getNmsItemId(item) == 0) {
            player.sendMessage("§cVous ne pouvez pas vendre de l'air !");
            return;
        }
        if (totalPrice <= 0L) {
            player.sendMessage("§cLe prix total doit tre sup!rieur 0.");
            return;
        }
        int avail = countItemsNms(player, item);
        if (avail < quantity) {
            HdvServerHandler.sendActionResult(player, false, "Vous n'avez pas assez d'items.");
            return;
        }
        removeItemsNms(player, item, quantity);
        item = item.clone();
        item.setAmount(quantity);
        // Si c'est un livre enchanté, appliquer le nom français + lore des enchantements
        if (EnchantUtils.isEnchantedBook(item)) {
            EnchantUtils.applyFrenchMeta(item);
        }
        int id = this.database.createListing(player.getUniqueId().toString(), player.getName(), item, totalPrice, quantity);
        if (id > 0) {
            HdvServerHandler.sendActionResult(player, true, "Mise en vente reussie !");
            sendListings(player, 0, "");
        } else {
            giveItem(player, item, quantity);
            if (id == -2) {
                HdvServerHandler.sendActionResult(player, false, "Limite d'annonces atteinte.");
            } else {
                HdvServerHandler.sendActionResult(player, false, "Erreur interne.");
            }
        }
    }

    private int countItemsNms(Player player, ItemStack prototype) {
        int refId = CustomPacketServerHandler.getNmsItemId(prototype);
        short refDmg = prototype.getDurability();
        int count = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && CustomPacketServerHandler.getNmsItemId(s) == refId && s.getDurability() == refDmg)
                count += s.getAmount();
        }
        return count;
    }

    private void removeItemsNms(Player player, ItemStack prototype, int amount) {
        int refId = CustomPacketServerHandler.getNmsItemId(prototype);
        short refDmg = prototype.getDurability();
        int left = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s != null && CustomPacketServerHandler.getNmsItemId(s) == refId && s.getDurability() == refDmg) {
                if (s.getAmount() <= left) {
                    left -= s.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - left);
                    left = 0;
                }
                if (left == 0)
                    break;
            }
        }
        player.updateInventory();
    }

    private void giveItem(Player player, ItemStack item, int qty) {
        ItemStack clone = item.clone();
        clone.setAmount(qty);
        player.getInventory().addItem(new ItemStack[] { clone }).values().forEach(remain -> player.getWorld().dropItem(player.getLocation(), remain));
    }

    public void handleCancelOffer(Player player, int listingId) {
        HdvListing listing = this.database.cancelListing(listingId, player.getUniqueId().toString());
        if (listing == null) {
            HdvServerHandler.sendActionResult(player, false, "Annonce introuvable ou non autorisee.");
            return;
        }
        if (listing.getItem() != null && listing.getQuantity() > 0) {
            giveItem(player, listing.getItem(), listing.getQuantity());
        }
        HdvServerHandler.sendActionResult(player, true, "Annonce retiree — item restitue !");
        // Envoyer la liste a jour pour que le client retire l'annonce de son menu
        Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            sendListings(player, 0, "");
            sendMyListings(player);
        }, 5L);
    }

    public void handleCollect(Player player) {
        long amount = this.database.collectEarnings(player.getUniqueId().toString());
        if (amount <= 0L) {
            HdvServerHandler.sendActionResult(player, false, "Aucun gain en attente.");
            player.sendMessage("§6[HDV] §7Aucun gain a collecter pour le moment.");
            return;
        }
        if (this.economy != null)
            this.economy.deposit(player, amount);
        long newBalance = (this.economy != null) ? this.economy.getBalance(player) : 0L;
        // Message riche en jeu
        player.sendMessage("§6┌──────────────────────────────────┐");
        player.sendMessage("§6│  §e Gains HDV collectes            §6│");
        player.sendMessage("§6│                                    §6│");
        player.sendMessage("§6│  §7Montant       : §a+" + fmt(amount) + " $");
        player.sendMessage("§6│  §7Nouveau solde : §6" + fmt(newBalance) + " $");
        player.sendMessage("§6└──────────────────────────────────┘");
        // Packet de confirmation (utilisé par le GUI pour afficher le message de statut)
        HdvServerHandler.sendActionResult(player, true, "Gains collectes : +" + fmt(amount) + " $ !");
        sendPlayerBalance(player);
        // Envoyer la liste mise a jour pour purger les items vendus du menu "Mes annonces"
        sendMyListings(player);
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(new ItemStack[] { item }).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.updateInventory();
    }

    private boolean hasEnoughItems(Player player, ItemStack ref, int qty) {
        int refId = CustomPacketServerHandler.getNmsItemId(ref);
        short refDmg = ref.getDurability();
        int count = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null) {
                if (CustomPacketServerHandler.getNmsItemId(s) == refId && s.getDurability() == refDmg)
                    count += s.getAmount();
                if (count >= qty)
                    return true;
            }
        }
        return (count >= qty);
    }

    private void removeItems(Player player, ItemStack ref, int qty) {
        int refId = CustomPacketServerHandler.getNmsItemId(ref);
        short refDmg = ref.getDurability();
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = qty;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s != null &&
                    CustomPacketServerHandler.getNmsItemId(s) == refId && s.getDurability() == refDmg)
                if (s.getAmount() <= remaining) {
                    remaining -= s.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - remaining);
                    remaining = 0;
                }
        }
        player.updateInventory();
    }

    private String fmt(long v) {
        if (v >= 1000000L)
            return String.format("%.1fM", new Object[] { Double.valueOf(v / 1000000.0D) });
        if (v >= 1000L)
            return String.format("%.1fK", new Object[] { Double.valueOf(v / 1000.0D) });
        return String.valueOf(v);
    }

    public static interface EconomyProvider {
        long getBalance(Player param1Player);

        boolean withdraw(Player param1Player, long param1Long);

        void deposit(Player param1Player, long param1Long);
    }
}
