package fr.originsfight.boutique;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.economy.VaultEconomy;
import fr.originsfight.pb.PBManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reçoit les requêtes Boutique du client : refresh + achat.
 * Canal : CUSTOM:BOUTIQUE_C2S
 */
public class BoutiqueClientServerHandler implements PluginMessageListener {

    public static final String CHANNEL_C2S = "CUSTOM:BOUTIQUE_C2S";

    private static final int BOUTIQUE_REQUEST = 0xB0;
    private static final int BOUTIQUE_BUY     = 0xB1;

    private final OriginsFightCore plugin;

    public BoutiqueClientServerHandler(OriginsFightCore plugin) {
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
        switch (cat) {
            case 0: buyConfig(player, "boutique.grades",    "grade",   id, payPB, temporary); break;
            case 1: buyConfig(player, "boutique.kits",      "kit",     id, payPB, false);      break;
            case 2: buyConfig(player, "boutique.commandes", "cmd",     id, payPB, temporary);  break;
            case 3: buyConfig(player, "boutique.spawners",  "spawner", id, payPB, false);      break;
            case 4: buyOffre(player, id, payPB); break;
            case 5: buyPack(player, id, payPB);  break;
            default:
                BoutiquePacketSender.sendResult(player, false, "Categorie inconnue.");
        }
    }

    @SuppressWarnings("unchecked")
    private void buyConfig(Player player, String path, String kind, String id, boolean payPB, boolean temporary) {
        List<?> entries = plugin.getBoutiqueConfig().getList(path);
        if (entries == null) {
            BoutiquePacketSender.sendResult(player, false, "Categorie vide.");
            return;
        }
        for (Object o : entries) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            if (!id.equalsIgnoreCase(String.valueOf(m.get("id")))) continue;

            // Choisir le bon prix selon permanent ou temporaire
            int prixPB    = asInt(m.get("prix_pb"));
            long prixM    = asLong(m.get("prix_monnaie"));
            int prixPBPerm  = asInt(m.get("prix_pb_perm"));
            long prixMPerm  = asLong(m.get("prix_monnaie_perm"));

            boolean effectivePerm = !temporary && prixMPerm > 0; // achat permanent si flag + prix dispo
            long usedPrixM  = effectivePerm ? prixMPerm  : prixM;
            int  usedPrixPB = effectivePerm ? prixPBPerm : prixPB;

            boolean effectivePB;
            if (usedPrixM > 0 && usedPrixPB > 0) effectivePB = payPB;
            else if (usedPrixPB > 0)              effectivePB = true;
            else                                  effectivePB = false;
            long price = effectivePB ? usedPrixPB : usedPrixM;
            if (price <= 0) {
                BoutiquePacketSender.sendResult(player, false, "Mode de paiement indisponible.");
                return;
            }
            if (!charge(player, effectivePB, price, kind + ":" + id)) return;
            executeRewards(player, m, kind, effectivePerm);
            BoutiquePacketSender.sendResult(player, true, "Achat effectue : " + stripColor(String.valueOf(m.get("nom"))));
            BoutiquePacketSender.sendData(player);
            return;
        }
        BoutiquePacketSender.sendResult(player, false, "Article introuvable.");
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

    @SuppressWarnings("unchecked")
    private void buyPack(Player player, String id, boolean payPB) {
        List<?> entries = plugin.getBoutiqueConfig().getList("boutique.packs");
        if (entries == null) {
            BoutiquePacketSender.sendResult(player, false, "Aucun pack disponible.");
            return;
        }
        for (Object o : entries) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            if (!id.equalsIgnoreCase(String.valueOf(m.get("id")))) continue;

            int  prixPB = asInt(m.get("prix_pb"));
            long prixM  = asLong(m.get("prix_monnaie"));

            boolean effectivePB;
            if (prixM > 0 && prixPB > 0) effectivePB = payPB;
            else if (prixPB > 0)         effectivePB = true;
            else                         effectivePB = false;
            long price = effectivePB ? prixPB : prixM;
            if (price <= 0) {
                BoutiquePacketSender.sendResult(player, false, "Mode de paiement indisponible.");
                return;
            }
            if (!charge(player, effectivePB, price, "pack:" + id)) return;

            // Exécuter toutes les récompenses du pack
            Object cmds = m.get("commandes");
            if (cmds instanceof List) {
                for (Object c : (List<Object>) cmds) dispatch(player, String.valueOf(c));
            }
            BoutiquePacketSender.sendResult(player, true, "Pack achete : " + stripColor(String.valueOf(m.get("nom"))));
            BoutiquePacketSender.sendData(player);
            return;
        }
        BoutiquePacketSender.sendResult(player, false, "Pack introuvable.");
    }

    // ── Paiement ─────────────────────────────────────────────────────────────

    private boolean charge(Player p, boolean payPB, long amount, String reason) {
        if (payPB) {
            PBManager mgr = plugin.getPBManager();
            if (mgr == null || !mgr.has(p, (int) amount)) {
                BoutiquePacketSender.sendResult(p, false, "PB insuffisants.");
                return false;
            }
            if (!mgr.remove(p, (int) amount, reason)) {
                BoutiquePacketSender.sendResult(p, false, "Erreur retrait PB.");
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

    // ── Récompenses ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void executeRewards(Player p, Map<String, Object> data, String kind, boolean permanent) {
        // Grades : liste de commandes
        Object cmds = data.get("commandes");
        if (cmds instanceof List) {
            for (Object c : (List<Object>) cmds) dispatch(p, String.valueOf(c));
            return;
        }
        // Commandes : une seule commande, permanent ou temporaire
        String cmd = permanent && data.get("commande_perm") != null
                ? String.valueOf(data.get("commande_perm"))
                : (data.get("commande") != null ? String.valueOf(data.get("commande")) : null);
        if (cmd == null) return;
        // Achat temporaire : injecter la durée LuckPerms depuis le champ "duree" (secondes)
        if (!permanent) cmd = cmd.replace("%duree%", lpDuration(asLong(data.get("duree"))));
        dispatch(p, cmd);
    }

    /** Convertit une duree en secondes vers un format accepte par LuckPerms (ex: 2592000 -> "2592000s"). */
    private static String lpDuration(long seconds) {
        if (seconds <= 0) seconds = 2592000L; // defaut : 30 jours
        return seconds + "s";
    }

    private void dispatch(Player p, String raw) {
        String resolved = raw
                .replace("%player%", p.getName())
                .replace("%uuid%",   p.getUniqueId().toString());
        if (resolved.startsWith("/")) resolved = resolved.substring(1);
        // Les "give" sont donnés via l'API Bukkit : elle connaît tous les Material
        // custom (Material.matchMaterial), contrairement à la base d'items figée
        // d'Essentials (items.csv) qu'il faudrait sinon maintenir à la main.
        if (giveNatively(resolved)) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
    }

    /**
     * Exécute nativement un "give <joueur> <ITEM[:data]> [quantité] [ench niveau ...]"
     * (syntaxe Essentials). Retourne true si la commande était un give (gérée ou
     * loggée en échec), false si ce n'est pas un give et qu'il faut le dispatcher.
     */
    private boolean giveNatively(String command) {
        String[] t = command.trim().split("\\s+");
        if (t.length < 3) return false;
        String head = t[0].toLowerCase(Locale.ROOT);
        int ns = head.indexOf(':');
        if (ns >= 0) head = head.substring(ns + 1); // minecraft:give / essentials:give
        if (!head.equals("give")) return false;

        Player target = Bukkit.getPlayerExact(t[1]);
        if (target == null) {
            plugin.getLogger().warning("[Boutique] give : joueur introuvable '" + t[1] + "' (" + command + ")");
            return true;
        }

        // Material, avec data optionnelle (ITEM:data)
        String matToken = t[2];
        short data = 0;
        int colon = matToken.indexOf(':');
        if (colon >= 0) {
            try { data = Short.parseShort(matToken.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            matToken = matToken.substring(0, colon);
        }
        Material mat = Material.matchMaterial(matToken);
        if (mat == null) {
            plugin.getLogger().warning("[Boutique] give : item inconnu '" + t[2] + "' (" + command + ")");
            return true;
        }

        int amount = 1;
        if (t.length >= 4) { try { amount = Integer.parseInt(t[3]); } catch (NumberFormatException ignored) {} }

        ItemStack item = new ItemStack(mat, amount, data);

        // Enchantements : paires "nom niveau" à partir du 5e token
        for (int i = 4; i + 1 < t.length; i += 2) {
            int level;
            try { level = Integer.parseInt(t[i + 1]); } catch (NumberFormatException e) { continue; }
            Enchantment ench = resolveEnchant(t[i]);
            if (ench != null) item.addUnsafeEnchantment(ench, level);
            else plugin.getLogger().warning("[Boutique] give : enchant inconnu '" + t[i] + "' (" + command + ")");
        }

        // addItem gère le découpage en stacks ; le surplus est lâché au sol
        for (ItemStack left : target.getInventory().addItem(item).values())
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        target.updateInventory();
        return true;
    }

    /** Résout un nom d'enchantement (alias Essentials ou nom Bukkit brut). */
    private static Enchantment resolveEnchant(String name) {
        Enchantment e = ENCHANTS.get(name.toLowerCase(Locale.ROOT).replace("_", ""));
        return e != null ? e : Enchantment.getByName(name.toUpperCase(Locale.ROOT));
    }

    private static final Map<String, Enchantment> ENCHANTS = new HashMap<>();
    static {
        // Armure
        ENCHANTS.put("protection",           Enchantment.PROTECTION_ENVIRONMENTAL);
        ENCHANTS.put("fireprotection",       Enchantment.PROTECTION_FIRE);
        ENCHANTS.put("featherfalling",       Enchantment.PROTECTION_FALL);
        ENCHANTS.put("blastprotection",      Enchantment.PROTECTION_EXPLOSIONS);
        ENCHANTS.put("projectileprotection", Enchantment.PROTECTION_PROJECTILE);
        ENCHANTS.put("respiration",          Enchantment.OXYGEN);
        ENCHANTS.put("aquaaffinity",         Enchantment.WATER_WORKER);
        ENCHANTS.put("thorns",               Enchantment.THORNS);
        ENCHANTS.put("depthstrider",         Enchantment.DEPTH_STRIDER);
        // Épée
        ENCHANTS.put("sharpness",            Enchantment.DAMAGE_ALL);
        ENCHANTS.put("smite",                Enchantment.DAMAGE_UNDEAD);
        ENCHANTS.put("baneofarthropods",     Enchantment.DAMAGE_ARTHROPODS);
        ENCHANTS.put("knockback",            Enchantment.KNOCKBACK);
        ENCHANTS.put("fireaspect",           Enchantment.FIRE_ASPECT);
        ENCHANTS.put("looting",              Enchantment.LOOT_BONUS_MOBS);
        // Outils
        ENCHANTS.put("efficiency",           Enchantment.DIG_SPEED);
        ENCHANTS.put("silktouch",            Enchantment.SILK_TOUCH);
        ENCHANTS.put("unbreaking",           Enchantment.DURABILITY);
        ENCHANTS.put("fortune",              Enchantment.LOOT_BONUS_BLOCKS);
        // Arc
        ENCHANTS.put("power",                Enchantment.ARROW_DAMAGE);
        ENCHANTS.put("punch",                Enchantment.ARROW_KNOCKBACK);
        ENCHANTS.put("flame",                Enchantment.ARROW_FIRE);
        ENCHANTS.put("infinity",             Enchantment.ARROW_INFINITE);
        // Canne à pêche
        ENCHANTS.put("luckofthesea",         Enchantment.LUCK);
        ENCHANTS.put("lure",                 Enchantment.LURE);
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

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) try { return Integer.parseInt((String) o); } catch (Exception ignored) {}
        return 0;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) try { return Long.parseLong((String) o); } catch (Exception ignored) {}
        return 0L;
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("(?i)[§&].", "");
    }
}
