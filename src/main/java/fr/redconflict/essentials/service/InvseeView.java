package fr.redconflict.essentials.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.UUID;

/**
 * Le miroir d'inventaire de {@code /invsee} : rangement, barre d'action <b>et
 * armure</b>, dans une seule fenêtre modifiable.
 *
 * <p><b>Pourquoi un miroir et non l'inventaire lui-même.</b> Ouvrir directement
 * {@code target.getInventory()} — ce que faisait la commande — donne au client
 * un coffre de 36 cases : l'API ne rend que le rangement, et les quatre pièces
 * d'armure ne sont nulle part. Les montrer impose de fabriquer notre propre
 * fenêtre, donc de recopier dans les deux sens.
 *
 * <p><b>La recopie est différentielle, et c'est tout le sujet.</b> Écrire le
 * miroir entier chez l'observé dupliquerait des objets : entre deux
 * rafraîchissements il continue de jouer — il jette une pierre, la case se vide
 * chez lui et pas encore dans le miroir — et un envoi en bloc la lui rendrait
 * alors qu'elle est déjà au sol. On ne transmet donc que les cases qui
 * <b>diffèrent de l'état lu au dernier rafraîchissement</b> : ce sont exactement
 * celles que le staff a touchées. Les autres ne sont jamais réécrites.
 *
 * <p>Reste une fenêtre de course de 250 ms : l'observé et le staff modifiant la
 * <i>même</i> case dans le même intervalle, c'est le staff qui gagne. C'est
 * inévitable pour un miroir, et sans commune mesure avec ce que ça remplace.
 *
 * <p>Disposition (45 cases, 5 rangées) — l'ordre de l'écran d'inventaire du
 * joueur, pas celui de l'API :
 * <pre>
 *   rangées 1-3  rangement      (cases 9-35 du joueur)
 *   rangée  4    barre d'action (cases 0-8)
 *   rangée  5    armure : casque, plastron, jambières, bottes, puis séparateurs
 * </pre>
 */
public final class InvseeView {

    /** 45 cases : 36 d'inventaire, 4 d'armure, 5 de séparation. */
    public static final int SIZE = 45;

    /** Première case d'armure du miroir ; les quatre suivent, casque → bottes. */
    public static final int ARMOR_FIRST = 36;

    /**
     * Case du miroir → case de l'inventaire du joueur, ou -1 (armure,
     * séparateur). C'est la seule table de correspondance du fichier : tout le
     * reste s'en déduit.
     */
    private static final int[] TO_PLAYER = new int[SIZE];
    static {
        Arrays.fill(TO_PLAYER, -1);
        // Rangement d'abord, barre d'action ensuite : l'inverse de l'ordre de
        // l'API, mais celui que le joueur a sous les yeux.
        for (int i = 0; i < 27; i++) TO_PLAYER[i] = 9 + i;
        for (int i = 0; i < 9; i++)  TO_PLAYER[27 + i] = i;
    }

    private final UUID target;
    private final Inventory view;

    /**
     * L'état lu chez l'observé au dernier rafraîchissement, case par case.
     * C'est la référence du différentiel — sans elle, impossible de distinguer
     * « le staff a posé quelque chose ici » de « cette case n'a pas bougé ».
     */
    private final ItemStack[] baseline = new ItemStack[SIZE];

    /**
     * L'observé s'en va : plus rien ne doit lui être écrit. Son inventaire est
     * sur le point d'être sauvegardé, et une écriture après coup se perdrait —
     * en laissant au staff les objets qu'il en a sortis.
     */
    private boolean dead;

    private InvseeView(UUID target, Inventory view) {
        this.target = target;
        this.view = view;
    }

    /** Fabrique le miroir et le remplit avec l'état courant de l'observé. */
    public static InvseeView of(Player target) {
        // 32 caractères maximum dans un titre de fenêtre 1.8 : « Inventaire de »
        // plus un pseudo de 16 tient tout juste.
        Inventory inventory = Bukkit.createInventory(null, SIZE, "Inventaire de " + target.getName());
        InvseeView mirror = new InvseeView(target.getUniqueId(), inventory);
        mirror.refresh(target);
        return mirror;
    }

    public Inventory inventory() {
        return view;
    }

    public UUID target() {
        return target;
    }

    public boolean isDead() {
        return dead;
    }

    /** Coupe définitivement l'écriture vers l'observé (déconnexion). */
    public void markDead() {
        this.dead = true;
    }

    /** Vrai si cette case du miroir n'est qu'un séparateur, jamais cliquable. */
    public static boolean isFiller(int viewSlot) {
        return viewSlot >= ARMOR_FIRST + 4 && viewSlot < SIZE;
    }

    // ── Recopie ──────────────────────────────────────────────────────────────

    /**
     * Observé → miroir. Remet aussi la référence du différentiel à niveau : ce
     * qui est lu ici devient « ce que le staff n'a pas touché ».
     */
    public void refresh(Player target) {
        PlayerInventory inventory = target.getInventory();
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack item;
            if (TO_PLAYER[slot] >= 0) {
                item = normalize(inventory.getItem(TO_PLAYER[slot]));
            } else if (isFiller(slot)) {
                // Le séparateur est sa propre référence : il ne part jamais vers
                // l'observé, et un clic dessus est annulé en amont.
                view.setItem(slot, filler());
                baseline[slot] = filler();
                continue;
            } else {
                item = normalize(armorOf(inventory, slot - ARMOR_FIRST));
            }
            view.setItem(slot, item);
            baseline[slot] = item == null ? null : item.clone();
        }
    }

    /**
     * Miroir → observé, <b>uniquement les cases modifiées par le staff</b>.
     *
     * @return vrai si quelque chose a été écrit — le seul cas où il faut
     *         rafraîchir la fenêtre de l'observé
     */
    public boolean apply(Player target) {
        if (dead || target == null || !target.isOnline()) return false;

        PlayerInventory inventory = target.getInventory();
        boolean written = false;

        for (int slot = 0; slot < SIZE; slot++) {
            if (isFiller(slot)) {
                // Un séparateur déplacé malgré tout : on le remet, sans rien
                // écrire chez l'observé.
                if (!same(normalize(view.getItem(slot)), filler())) view.setItem(slot, filler());
                continue;
            }

            ItemStack now = normalize(view.getItem(slot));
            if (same(now, baseline[slot])) continue;   // pas touché : on n'y revient pas

            if (TO_PLAYER[slot] >= 0) {
                inventory.setItem(TO_PLAYER[slot], now);
            } else {
                setArmor(inventory, slot - ARMOR_FIRST, now);
            }
            baseline[slot] = now == null ? null : now.clone();
            written = true;
        }

        if (written) target.updateInventory();
        return written;
    }

    // ── Armure ───────────────────────────────────────────────────────────────

    /** 0 casque, 1 plastron, 2 jambières, 3 bottes. */
    private static ItemStack armorOf(PlayerInventory inventory, int index) {
        switch (index) {
            case 0:  return inventory.getHelmet();
            case 1:  return inventory.getChestplate();
            case 2:  return inventory.getLeggings();
            default: return inventory.getBoots();
        }
    }

    private static void setArmor(PlayerInventory inventory, int index, ItemStack item) {
        switch (index) {
            case 0:  inventory.setHelmet(item);     break;
            case 1:  inventory.setChestplate(item); break;
            case 2:  inventory.setLeggings(item);   break;
            default: inventory.setBoots(item);      break;
        }
    }

    // ── Comparaison ──────────────────────────────────────────────────────────

    /**
     * Vide et « pile d'air » sont la même chose : l'API rend l'un ou l'autre
     * selon l'endroit, et les confondre ferait voir une différence là où il n'y
     * en a pas — donc réécrire une case intacte.
     */
    private static ItemStack normalize(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item;
    }

    /** Égalité complète — type, quantité, durabilité, méta comprises. */
    private static boolean same(ItemStack a, ItemStack b) {
        return a == null ? b == null : a.equals(b);
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            // Sans nom, le client affiche « Gray Stained Glass Pane » au survol.
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        return pane;
    }
}
