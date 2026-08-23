package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.pb.PBManager;
import fr.redconflict.site.EntitlementService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Reçoit les requêtes Boutique du client : refresh + achat.
 * Canal : CUSTOM:BOUTIQUE_C2S
 *
 * <p>Ne décide plus de ce qu'un article donne — {@link RewardDispatcher} s'en
 * charge, et la boutique du site l'appelle aussi. Ici il ne reste que la
 * séquence d'un achat : vérifier qu'il a lieu d'être, encaisser, livrer,
 * enregistrer.
 */
public class BoutiqueClientServerHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:BOUTIQUE_C2S";

    private static final int BOUTIQUE_REQUEST = 0xB0;
    private static final int BOUTIQUE_BUY     = 0xB1;

    /** Catégories telles que le client les numérote dans le paquet d'achat. */
    private static final String[] CATEGORIES = { "grade", "kit", "cmd", "spawner", null, "pack" };

    private final RedConflictCore plugin;

    public BoutiqueClientServerHandler(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_C2S.equals(channel)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            int packetId = readVarInt(in);
            switch (packetId) {
                case BOUTIQUE_REQUEST:
                    Bukkit.getScheduler().runTask(plugin, () -> BoutiquePacketSender.sendData(player));
                    break;
                case BOUTIQUE_BUY: {
                    int cat = in.readUnsignedByte();
                    String id = readString(in, 64);
                    boolean payPB = in.readBoolean();
                    boolean temporary = in.readBoolean();
                    Bukkit.getScheduler().runTask(plugin, () -> handleBuy(player, cat, id, payPB, temporary));
                    break;
                }
                default: break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Boutique] Packet C2S invalide : " + e.getMessage());
        }
    }

    // ── Achat ─────────────────────────────────────────────────────────────────

    private void handleBuy(Player player, int cat, String id, boolean payPB, boolean temporary) {
        // Les offres spéciales ont leur propre cycle de vie (stock, expiration) :
        // elles ne passent pas par le catalogue.
        if (cat == 4) { buyOffre(player, id, payPB); return; }

        if (cat < 0 || cat >= CATEGORIES.length || CATEGORIES[cat] == null) {
            BoutiquePacketSender.sendResult(player, false, "Categorie inconnue.");
            return;
        }
        buyItem(player, CATEGORIES[cat], id, payPB, temporary);
    }

    private void buyItem(Player player, String category, String id, boolean payPB, boolean temporary) {
        BoutiqueItem item = plugin.getBoutiqueCatalog().find(category, id);
        if (item == null) {
            BoutiquePacketSender.sendResult(player, false, "Article introuvable.");
            return;
        }

        // Un article sans mode temporaire est toujours acquis définitivement,
        // quoi que demande le client : kits, spawners et packs sont dans ce cas.
        boolean permanent = !temporary || !item.supportsTemporary();

        // ── Le verrou : on ne revend pas ce que le joueur possède déjà ────────
        EntitlementService entitlements = plugin.getEntitlementService();
        if (entitlements != null) {
            String denial = entitlements.denialReason(player, item, permanent);
            if (denial != null) {
                BoutiquePacketSender.sendResult(player, false, denial);
                return;
            }
        }

        int pbPrice = item.pbPriceFor(permanent);
        long moneyPrice = item.moneyPriceFor(permanent);

        boolean usePB;
        if (pbPrice > 0 && moneyPrice > 0) usePB = payPB;
        else if (pbPrice > 0)              usePB = true;
        else                               usePB = false;

        long price = usePB ? pbPrice : moneyPrice;
        if (price <= 0) {
            BoutiquePacketSender.sendResult(player, false, "Mode de paiement indisponible.");
            return;
        }

        if (!charge(player, usePB, price, item.category + ":" + item.id)) return;

        plugin.getRewardDispatcher().execute(player.getName(), player.getUniqueId(), item, permanent);
        if (entitlements != null) {
            entitlements.grant(player, item, permanent, "game");
        }

        BoutiquePacketSender.sendResult(player, true, "Achat effectue : " + item.name);
        BoutiquePacketSender.sendData(player);
    }

    private void buyOffre(Player player, String id, boolean payPB) {
        OffresManager mgr = plugin.getOffresManager();
        OffreSpeciale cur = mgr != null ? mgr.getCurrent() : null;
        if (cur == null || !cur.id.equals(id) || cur.stock <= 0) {
            BoutiquePacketSender.sendResult(player, false, "L'offre n'est plus disponible.");
            BoutiquePacketSender.sendData(player);
            return;
        }
        boolean effectivePB;
        if (cur.prixMonnaie > 0 && cur.prixPB > 0) effectivePB = payPB;
        else if (cur.prixPB > 0)                   effectivePB = true;
        else                                       effectivePB = false;
        long price = effectivePB ? cur.prixPB : cur.prixMonnaie;
        if (price <= 0) {
            BoutiquePacketSender.sendResult(player, false, "Mode de paiement indisponible.");
            return;
        }
        if (!charge(player, effectivePB, price, "offre:" + id)) return;

        // Recheck stock post-paiement
        OffreSpeciale post = mgr.getCurrent();
        if (post == null || !post.id.equals(id) || post.stock <= 0) {
            refund(player, effectivePB, price, "offre_indispo:" + id);
            BoutiquePacketSender.sendResult(player, false, "Stock épuisé pendant l'achat — remboursé.");
            BoutiquePacketSender.sendData(player);
            return;
        }
        player.getInventory().addItem(cur.buildPurchasable());
        mgr.consumeStock();
        BoutiquePacketSender.sendResult(player, true, "Offre spéciale acquise !");
        BoutiquePacketSender.sendData(player);
    }

    // ── Paiement ─────────────────────────────────────────────────────────────

    private boolean charge(Player p, boolean payPB, long amount, String reason) {
        if (payPB) {
            PBManager mgr = plugin.getPBManager();
            if (mgr == null) {
                BoutiquePacketSender.sendResult(p, false, "Systeme PB indisponible.");
                return false;
            }
            // Le solde PB vit dans la base du site : si elle est injoignable, on
            // refuse plutôt que de livrer un article qui ne serait jamais payé.
            if (!mgr.isAvailable()) {
                BoutiquePacketSender.sendResult(p, false, "Boutique PB momentanement indisponible.");
                return false;
            }
            if (!mgr.remove(p, (int) amount, reason)) {
                BoutiquePacketSender.sendResult(p, false, "PB insuffisants.");
                return false;
            }
            return true;
        }
        if (VaultEconomy.get() == null) {
            BoutiquePacketSender.sendResult(p, false, "Économie indisponible.");
            return false;
        }
        if (VaultEconomy.get().getBalance(p) < amount) {
            BoutiquePacketSender.sendResult(p, false, "Argent insuffisant.");
            return false;
        }
        if (!VaultEconomy.get().withdrawPlayer(p, amount).transactionSuccess()) {
            BoutiquePacketSender.sendResult(p, false, "Erreur retrait monnaie.");
            return false;
        }
        return true;
    }

    private void refund(Player p, boolean payPB, long amount, String reason) {
        if (payPB && plugin.getPBManager() != null)
            plugin.getPBManager().add(p, (int) amount, "REFUND:" + reason);
        else if (VaultEconomy.get() != null)
            VaultEconomy.get().depositPlayer(p, amount);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("EOF");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 35) throw new IOException("VarInt overflow");
        }
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > max * 4) throw new IOException("String too long");
        byte[] data = new byte[len];
        in.readFully(data);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
