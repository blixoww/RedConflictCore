package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Exécute les récompenses d'un article de la boutique.
 *
 * <p><b>Le seul endroit qui livre.</b> L'achat en jeu et l'achat sur le site
 * passent tous les deux ici, avec le même {@link BoutiqueItem} : c'est ce qui
 * garantit qu'un grade acheté sur le web donne exactement les mêmes permissions
 * que le même grade acheté au comptoir, sans qu'on ait à tenir deux listes.
 *
 * <p>Le travail se fait à partir d'un <b>pseudo</b>, pas d'un {@link Player} :
 * une livraison venue du site peut viser un joueur déconnecté. Les commandes
 * LuckPerms et {@code mspa} s'en accommodent — elles acceptent une cible hors
 * ligne. Les {@code give} non : voir {@link #requiresOnline}.
 */
public final class RewardDispatcher {

    private final RedConflictCore plugin;

    public RewardDispatcher(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Un article dont une récompense atterrit dans l'inventaire ne peut être
     * livré qu'à un joueur connecté. Le site s'en sert pour poser
     * {@code need_online} sur la commande AzLink, et attendre la connexion plutôt
     * que de livrer dans le vide.
     */
    public static boolean requiresOnline(BoutiqueItem item, boolean permanent) {
        for (String line : item.commandsFor(permanent)) {
            if (lineRequiresOnline(line)) return true;
        }
        return false;
    }

    /**
     * Une ligne de récompense dépose-t-elle quelque chose dans l'inventaire ?
     * {@code give}, {@code givekey} et {@code kit} : les trois attendent le joueur.
     * Partagée avec les lots de vote, pour que les deux ne divergent pas.
     */
    public static boolean lineRequiresOnline(String line) {
        String[] t = line.trim().replaceFirst("^/", "").split("\\s+");
        if (t.length < 2) return false;
        String head = t[0].toLowerCase(Locale.ROOT);
        int ns = head.indexOf(':');
        if (ns >= 0) head = head.substring(ns + 1);
        return head.equals("give") || head.equals("givekey") || head.equals("kit");
    }

    /**
     * Applique les récompenses. Doit tourner sur le thread principal : elle
     * touche l'inventaire et la console.
     *
     * @param permanent achat à vie — sinon les nœuds sont posés en {@code settemp}
     */
    public void execute(String playerName, UUID uuid, BoutiqueItem item, boolean permanent) {
        String duration = lpDuration(item.durationSeconds);
        for (String raw : item.commandsFor(permanent)) {
            String line = raw.replace("%duree%", duration);
            dispatch(playerName, uuid, line);
        }
    }

    /** Exécute une ligne brute, en substituant la cible. */
    public void dispatch(String playerName, UUID uuid, String raw) {
        String resolved = raw
                .replace("%player%", playerName)
                .replace("%uuid%", uuid != null ? uuid.toString() : "");
        if (resolved.startsWith("/")) resolved = resolved.substring(1);
        // Les "give" passent par l'API Bukkit : elle connaît les Material custom
        // (Material.matchMaterial), contrairement à la base d'items figée
        // d'Essentials (items.csv) qu'il faudrait sinon maintenir à la main.
        if (giveNatively(resolved)) return;
        if (kitNatively(resolved)) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
    }

    /** Convertit une durée en secondes vers le format LuckPerms (2592000 → « 2592000s »). */
    public static String lpDuration(long seconds) {
        if (seconds <= 0) seconds = 2592000L; // défaut : 30 jours
        return seconds + "s";
    }

    private static boolean isGive(String command) {
        String[] t = command.trim().split("\\s+");
        if (t.length < 3) return false;
        String head = t[0].toLowerCase(Locale.ROOT);
        int ns = head.indexOf(':');
        if (ns >= 0) head = head.substring(ns + 1); // minecraft:give / essentials:give
        return head.equals("give");
    }

    /**
     * Exécute nativement un {@code give <joueur> <ITEM[:data]> [quantité] [ench niveau …]}
     * (syntaxe Essentials). Retourne {@code true} si la commande était un give
     * (traitée, ou loggée en échec), {@code false} s'il faut la dispatcher.
     */
    private boolean giveNatively(String command) {
        if (!isGive(command)) return false;
        String[] t = command.trim().split("\\s+");

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
            try { data = Short.parseShort(matToken.substring(colon + 1)); } catch (NumberFormatException ignored) { }
            matToken = matToken.substring(0, colon);
        }
        Material mat = Material.matchMaterial(matToken);
        if (mat == null) {
            plugin.getLogger().warning("[Boutique] give : item inconnu '" + t[2] + "' (" + command + ")");
            return true;
        }

        int amount = 1;
        if (t.length >= 4) { try { amount = Integer.parseInt(t[3]); } catch (NumberFormatException ignored) { } }

        ItemStack item = new ItemStack(mat, amount, data);

        // Enchantements : paires "nom niveau" à partir du 5e token
        for (int i = 4; i + 1 < t.length; i += 2) {
            int level;
            try { level = Integer.parseInt(t[i + 1]); } catch (NumberFormatException e) { continue; }
            Enchantment ench = resolveEnchant(t[i]);
            // Sur un livre, l'enchantement se range dans StoredEnchantments et
            // nulle part ailleurs : posé comme sur un équipement, il produit un
            // livre d'apparence normale que l'enclume refuse. Voir EnchantUtils.
            if (ench != null) fr.redconflict.hdv.EnchantUtils.apply(item, ench, level);
            else plugin.getLogger().warning("[Boutique] give : enchant inconnu '" + t[i] + "' (" + command + ")");
        }

        // addItem gère le découpage en stacks ; le surplus est lâché au sol
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        target.updateInventory();
        return true;
    }

    /**
     * {@code kit <nom> <joueur>} — remise directe d'un kit GreatKits.
     *
     * <p><b>Pourquoi pas la commande.</b> Le {@code /kit} de GreatKits commence
     * par {@code sender instanceof Player} : lancé depuis la console il ne fait
     * rien, sans un mot, et n'a aucune variante « donner à un autre joueur ».
     * Les lots « kit … » du vote ne livraient donc jamais rien. On lit ici le
     * fichier du kit ({@code plugins/GreatKits/Kits/<nom>.yml}) et on remet son
     * contenu par l'API Bukkit, comme les {@code give}. Pas de cooldown ni de
     * permission : c'est une récompense, pas un kit réclamé.
     *
     * @return {@code true} si la ligne était un kit (remis, ou échec journalisé)
     */
    private boolean kitNatively(String command) {
        String[] t = command.trim().split("\\s+");
        if (t.length != 3) return false;
        String head = t[0].toLowerCase(Locale.ROOT);
        int ns = head.indexOf(':');
        if (ns >= 0) head = head.substring(ns + 1);
        if (!head.equals("kit")) return false;

        File dir = new File(plugin.getDataFolder().getParentFile(), "GreatKits/Kits");
        File file = findKitFile(dir, t[1]);
        if (file == null) {
            // Pas un kit GreatKits connu : on laisse la commande suivre son cours
            // normal (autre plugin de kits qui, lui, accepterait la console).
            return false;
        }

        Player target = Bukkit.getPlayerExact(t[2]);
        if (target == null) {
            plugin.getLogger().warning("[Boutique] kit : joueur introuvable '" + t[2] + "' (" + command + ")");
            return true;
        }

        YamlConfiguration kit = YamlConfiguration.loadConfiguration(file);
        int given = 0;
        for (String section : new String[] { "Inventory.Main", "Inventory.Armor" }) {
            List<?> items = kit.getList(section);
            if (items == null) continue;
            for (Object o : items) {
                if (!(o instanceof ItemStack)) continue;          // cases vides : null
                ItemStack stack = ((ItemStack) o).clone();
                if (stack.getType() == Material.AIR) continue;
                for (ItemStack left : target.getInventory().addItem(stack).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), left);
                }
                given++;
            }
        }
        target.updateInventory();
        if (given == 0) {
            plugin.getLogger().warning("[Boutique] kit : '" + file.getName() + "' est vide (" + command + ")");
        }
        return true;
    }

    /**
     * Fichier du kit, insensible à la casse. Accepte aussi un nom qui ne diffère
     * que par un suffixe (« Potions » pour {@code potion.yml}) s'il désigne un
     * seul kit : c'est précisément l'écart qui traînait dans la table de vote.
     */
    private static File findKitFile(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        String wanted = name.toLowerCase(Locale.ROOT);
        File prefixMatch = null;
        int prefixCount = 0;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".yml")) continue;
            n = n.substring(0, n.length() - 4);
            if (n.equals(wanted)) return f;
            if (wanted.startsWith(n) || n.startsWith(wanted)) { prefixMatch = f; prefixCount++; }
        }
        return prefixCount == 1 ? prefixMatch : null;
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
}
