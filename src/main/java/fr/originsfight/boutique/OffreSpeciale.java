package fr.originsfight.boutique;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Définition d'une offre spéciale (config) + instance active (stock, expiresAt). */
public class OffreSpeciale {

    public String id;
    public boolean actif;
    public Material item;
    public short data = 0;
    public String nom;
    public List<String> lore = new ArrayList<>();
    public Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
    public int prixMonnaie;
    public int prixPB;
    public int duree;          // secondes
    public int stockInitial;
    public int stock;
    public long expiresAt;

    public static OffreSpeciale fromMap(Map<?, ?> m) {
        OffreSpeciale o = new OffreSpeciale();
        o.id = String.valueOf(m.get("id"));
        Object a = m.get("actif");
        o.actif = (a == null) || Boolean.parseBoolean(String.valueOf(a));
        Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
        o.item = mat != null ? mat : Material.STONE;
        Object dv = m.get("data");
        if (dv instanceof Number) o.data = ((Number) dv).shortValue();
        o.nom = String.valueOf(m.get("nom"));
        if (m.get("lore") instanceof List) {
            for (Object s : (List<?>) m.get("lore")) o.lore.add(String.valueOf(s));
        }
        if (m.get("enchantements") instanceof List) {
            for (Object e : (List<?>) m.get("enchantements")) {
                String[] sp = String.valueOf(e).split(":");
                if (sp.length == 2) {
                    Enchantment en = Enchantment.getByName(sp[0]);
                    if (en != null) {
                        try { o.enchants.put(en, Integer.parseInt(sp[1])); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        o.prixMonnaie = asInt(m.get("prix_monnaie"));
        o.prixPB = asInt(m.get("prix_pb"));
        o.duree = asInt(m.get("duree"));
        o.stockInitial = asInt(m.get("quantite_stock"));
        o.stock = o.stockInitial;
        return o;
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) { try { return Integer.parseInt((String) o); } catch (Exception ignored) {} }
        return 0;
    }

    /** Clone "definition" pour generer une nouvelle instance active. */
    public OffreSpeciale newInstance() {
        OffreSpeciale c = new OffreSpeciale();
        c.id = id; c.actif = actif; c.item = item; c.data = data; c.nom = nom;
        c.lore = new ArrayList<>(lore);
        c.enchants = new LinkedHashMap<>(enchants);
        c.prixMonnaie = prixMonnaie; c.prixPB = prixPB;
        c.duree = duree; c.stockInitial = stockInitial; c.stock = stockInitial;
        c.expiresAt = System.currentTimeMillis() + (long) duree * 1000L;
        return c;
    }

    /** Construit l'item donne au joueur apres achat (nom + lore + enchantements + data). */
    public ItemStack buildPurchasable() {
        ItemStack s = BoutiqueItems.build(item, 1, data, nom, lore);
        return BoutiqueItems.withEnchants(s, enchants);
    }
}
