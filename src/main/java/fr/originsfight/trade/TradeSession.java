package fr.originsfight.trade;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Représente une session de trade entre deux joueurs.
 *
 * Fonctionnement :
 *  - Chaque joueur a 18 slots pour poser ses items (colonnes gauche/droite)
 *  - 1 slot "Confirmer" et 1 slot "Annuler" par joueur
 *  - Le trade ne s'exécute QUE si les deux joueurs ont confirmé
 *  - Anti-duplication : les items sont retirés AVANT d'être donnés, atomiquement
 */
public class TradeSession {

    // ── Layout de l'inventaire (54 slots, 6 lignes × 9 colonnes) ──
    // Slots 0-17  : offre du joueur A (colonnes 0-1)
    // Slots 18-35 : séparateur + statut (colonne 4)  [slots 4,13,22,31,40,49 = centre]
    // Slots 27-44 : offre du joueur B (colonnes 7-8)
    // Slot  22    : statut joueur A
    // Slot  31    : statut joueur B
    // Slot  45    : bouton Confirmer joueur A
    // Slot  46    : bouton Annuler joueur A
    // Slot  52    : bouton Confirmer joueur B
    // Slot  53    : bouton Annuler joueur B

    // Slots offer joueur A (2 colonnes gauche, lignes 0-5)
    public static final int[] SLOTS_A = {0,1,9,10,18,19,27,28,36,37,45,46,54,55,63,64,72,73};
    // Slots offer joueur B (2 colonnes droite)
    public static final int[] SLOTS_B = {7,8,16,17,25,26,34,35,43,44,52,53};

    // On utilise un inventaire de 54 slots, layout simplifié :
    // Ligne 0 : [A][A][A][ ][SEP][ ][B][B][B]
    // Ligne 1 : [A][A][A][ ][SEP][ ][B][B][B]
    // Ligne 2 : [A][A][A][ ][STA][ ][B][B][B]
    // Ligne 3 : [A][A][A][ ][STB][ ][B][B][B]
    // Ligne 4 : [A][A][A][ ][SEP][ ][B][B][B]
    // Ligne 5 : [OK][X][ ][ ][SEP][ ][ ][OK][X]

    // Colonnes pour A : 0,1,2  → slots 0-2, 9-11, 18-20, 27-29, 36-38
    // Colonnes pour B : 6,7,8  → slots 6-8, 15-17, 24-26, 33-35, 42-44
    public static final Set<Integer> OFFER_SLOTS_A = new HashSet<>(Arrays.asList(
            0,1,2, 9,10,11, 18,19,20, 27,28,29, 36,37,38
    ));
    public static final Set<Integer> OFFER_SLOTS_B = new HashSet<>(Arrays.asList(
            6,7,8, 15,16,17, 24,25,26, 33,34,35, 42,43,44
    ));

    public static final int SLOT_CONFIRM_A = 45;
    public static final int SLOT_CANCEL_A  = 46;
    public static final int SLOT_CONFIRM_B = 53;
    public static final int SLOT_CANCEL_B  = 52;
    public static final int SLOT_STATUS_A  = 49; // statut "en attente" joueur A
    public static final int SLOT_STATUS_B  = 50; // statut "en attente" joueur B

    // Slots de séparation (colonne 4 + slot centre)
    public static final Set<Integer> SEPARATOR_SLOTS = new HashSet<>(Arrays.asList(
            3,4,5, 12,13,14, 21,22,23, 30,31,32, 39,40,41, 47,48,51
    ));

    public enum State { WAITING, CONFIRMED_A, CONFIRMED_B, BOTH_CONFIRMED, CANCELLED, DONE }

    private final Player playerA;
    private final Player playerB;
    private final Inventory inventory;
    private State state = State.WAITING;

    // Verrou anti-duplication : pendant l'exécution du trade, on bloque tout
    private boolean executing = false;

    public TradeSession(Player playerA, Player playerB) {
        this.playerA = playerA;
        this.playerB = playerB;
        this.inventory = buildInventory();
    }

    private Inventory buildInventory() {
        String title = ChatColor.DARK_GRAY + "Trade: "
                + ChatColor.YELLOW + playerA.getName()
                + ChatColor.GRAY + " ↔ "
                + ChatColor.AQUA + playerB.getName();

        org.bukkit.Bukkit.createInventory(null, 54, title);
        Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, title);

        // Séparateurs
        ItemStack sep = makeGlass(Material.STAINED_GLASS_PANE, (byte) 7, " ");
        for (int slot : SEPARATOR_SLOTS) inv.setItem(slot, sep);

        // Labels des offres
        inv.setItem(SLOT_STATUS_A, makeStatus(playerA, false));
        inv.setItem(SLOT_STATUS_B, makeStatus(playerB, false));

        // Boutons
        inv.setItem(SLOT_CONFIRM_A, makeConfirmButton(false));
        inv.setItem(SLOT_CANCEL_A,  makeCancelButton());
        inv.setItem(SLOT_CONFIRM_B, makeConfirmButton(false));
        inv.setItem(SLOT_CANCEL_B,  makeCancelButton());

        return inv;
    }

    // ── Méthodes appelées par le Listener ──────────────────────────────────────

    public void confirmA() {
        if (executing || state == State.CANCELLED || state == State.DONE) return;
        if (state == State.CONFIRMED_B) {
            state = State.BOTH_CONFIRMED;
            executeTrade();
        } else {
            state = State.CONFIRMED_A;
            inventory.setItem(SLOT_STATUS_A, makeStatus(playerA, true));
            inventory.setItem(SLOT_CONFIRM_A, makeConfirmButton(true));
            broadcastUpdate();
        }
    }

    public void confirmB() {
        if (executing || state == State.CANCELLED || state == State.DONE) return;
        if (state == State.CONFIRMED_A) {
            state = State.BOTH_CONFIRMED;
            executeTrade();
        } else {
            state = State.CONFIRMED_B;
            inventory.setItem(SLOT_STATUS_B, makeStatus(playerB, true));
            inventory.setItem(SLOT_CONFIRM_B, makeConfirmButton(true));
            broadcastUpdate();
        }
    }

    public void cancel(Player who) {
        if (state == State.DONE || state == State.CANCELLED) return;
        state = State.CANCELLED;
        returnItems();
        String canceller = who.getName();
        playerA.sendMessage(ChatColor.RED + "✖ Le trade a été annulé par " + canceller + ".");
        playerB.sendMessage(ChatColor.RED + "✖ Le trade a été annulé par " + canceller + ".");
        playerA.closeInventory();
        playerB.closeInventory();
    }

    /**
     * Appelé si un joueur ferme l'inventaire sans annuler explicitement.
     */
    public void onClose(Player who) {
        if (state == State.DONE || state == State.CANCELLED) return;
        cancel(who);
    }

    /**
     * Doit être appelé si un joueur retire un item de sa zone d'offre après avoir confirmé.
     * Cela réinitialise sa confirmation.
     */
    public void unconfirmA() {
        if (state == State.CONFIRMED_A) {
            state = State.WAITING;
            inventory.setItem(SLOT_STATUS_A, makeStatus(playerA, false));
            inventory.setItem(SLOT_CONFIRM_A, makeConfirmButton(false));
            broadcastUpdate();
        }
    }

    public void unconfirmB() {
        if (state == State.CONFIRMED_B) {
            state = State.WAITING;
            inventory.setItem(SLOT_STATUS_B, makeStatus(playerB, false));
            inventory.setItem(SLOT_CONFIRM_B, makeConfirmButton(false));
            broadcastUpdate();
        }
    }

    // ── Exécution atomique du trade ────────────────────────────────────────────

    /**
     * Exécute le trade de façon atomique et anti-duplication :
     * 1. On collecte les items des deux zones
     * 2. On vide les zones dans l'inventaire
     * 3. On donne les items à chaque joueur
     * 4. Ce qui ne rentre pas est droppé au sol
     */
    private void executeTrade() {
        executing = true;

        // Collecter offre A
        List<ItemStack> offerA = collectItems(OFFER_SLOTS_A);
        // Collecter offre B
        List<ItemStack> offerB = collectItems(OFFER_SLOTS_B);

        // Vider les slots dans l'inventaire (anti-dupe : les items sont maintenant "nulle part")
        for (int slot : OFFER_SLOTS_A) inventory.setItem(slot, null);
        for (int slot : OFFER_SLOTS_B) inventory.setItem(slot, null);

        state = State.DONE;
        playerA.closeInventory();
        playerB.closeInventory();

        // Donner offerB à joueur A, offerA à joueur B
        giveItems(playerA, offerB);
        giveItems(playerB, offerA);

        playerA.sendMessage(ChatColor.GREEN + "✔ Trade effectué avec succès avec " + ChatColor.GOLD + playerB.getName() + ChatColor.GREEN + " !");
        playerB.sendMessage(ChatColor.GREEN + "✔ Trade effectué avec succès avec " + ChatColor.GOLD + playerA.getName() + ChatColor.GREEN + " !");
    }

    /**
     * En cas d'annulation, on rend les items à leurs propriétaires.
     */
    private void returnItems() {
        List<ItemStack> offerA = collectItems(OFFER_SLOTS_A);
        List<ItemStack> offerB = collectItems(OFFER_SLOTS_B);
        for (int slot : OFFER_SLOTS_A) inventory.setItem(slot, null);
        for (int slot : OFFER_SLOTS_B) inventory.setItem(slot, null);
        giveItems(playerA, offerA);
        giveItems(playerB, offerB);
    }

    private List<ItemStack> collectItems(Set<Integer> slots) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        return items;
    }

    private void giveItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            // Si l'inventaire est plein, dropper au sol à la position du joueur
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                player.sendMessage(ChatColor.YELLOW + "⚠ Un item n'a pas pu rentrer dans votre inventaire et a été droppé à vos pieds !");
            }
        }
    }

    private void broadcastUpdate() {
        // Mettre à jour l'inventaire pour les deux joueurs
        playerA.updateInventory();
        playerB.updateInventory();
    }

    // ── Helpers de construction d'items UI ────────────────────────────────────

    private ItemStack makeStatus(Player player, boolean confirmed) {
        Material mat = confirmed ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK;
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((confirmed ? ChatColor.GREEN + "✔ " : ChatColor.RED + "✖ ")
                + ChatColor.YELLOW + player.getName()
                + (confirmed ? ChatColor.GREEN + " — Prêt !" : ChatColor.GRAY + " — En attente..."));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeConfirmButton(boolean alreadyConfirmed) {
        ItemStack item = new ItemStack(alreadyConfirmed ? Material.EMERALD : Material.DIAMOND, 1);
        ItemMeta meta = item.getItemMeta();
        if (alreadyConfirmed) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ Confirmé !");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "En attente de l'autre joueur..."));
        } else {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Confirmer le trade");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Cliquez pour accepter",
                    ChatColor.GRAY + "l'échange proposé."
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeCancelButton() {
        ItemStack item = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✖ Annuler");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Annule le trade et vous", ChatColor.GRAY + "rend vos items."));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeGlass(Material mat, byte data, String name) {
        ItemStack item = new ItemStack(mat, 1, data);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Player getPlayerA() { return playerA; }
    public Player getPlayerB() { return playerB; }
    public Inventory getInventory() { return inventory; }
    public State getState() { return state; }
    public boolean isActive() { return state != State.CANCELLED && state != State.DONE; }
}

