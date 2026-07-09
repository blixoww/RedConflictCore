package fr.redconflict.trade;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.core.text.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TradeSession {

    public static final int MAX_OFFER = 15;

    public enum State { WAITING, CONFIRMED_A, CONFIRMED_B, DONE, CANCELLED }

    /** Régions cliquables (doit correspondre aux constantes client). */
    public static final int REGION_INV   = 0;
    public static final int REGION_OFFER = 1;

    /** Bouton « collect » (double-clic gauche) — rassemble les piles identiques sur le curseur. */
    public static final int BUTTON_COLLECT = 2;

    private final Player playerA;
    private final Player playerB;
    /** Offres modélisées comme un vrai inventaire : 15 slots fixes (trous autorisés). */
    private final ItemStack[] offerA = new ItemStack[MAX_OFFER];
    private final ItemStack[] offerB = new ItemStack[MAX_OFFER];
    /** Item « porté » par chaque joueur (curseur), retiré de son inventaire le temps de la manip. */
    private ItemStack cursorA = null;
    private ItemStack cursorB = null;
    private long moneyA = 0L;
    private long moneyB = 0L;
    private int  pbA    = 0;
    private int  pbB    = 0;
    private State state = State.WAITING;
    private boolean executing = false;

    public TradeSession(Player a, Player b) {
        this.playerA = a;
        this.playerB = b;
    }

    // ── Item actions ─────────────────────────────────────────────────────────

    /**
     * Point d'entrée unique des clics (modèle serveur-autoritaire, façon
     * {@code Container.slotClick} vanilla). Le client n'altère jamais l'état
     * lui-même : il transmet (région, slot, bouton, shift) et on résout ici le
     * déplacement curseur ↔ inventaire ↔ offre, puis on resynchronise.
     *
     * @param region {@link #REGION_INV} ou {@link #REGION_OFFER}
     * @param button 0 = clic gauche, 1 = clic droit
     * @param shift  shift maintenu → quick-move (transfert rapide de stack)
     */
    public void handleClick(Player player, int region, int slot, int button, boolean shift) {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        boolean isA = playerA.equals(player);
        boolean isB = playerB.equals(player);
        if (!isA && !isB) return;

        boolean changed;
        if (button == BUTTON_COLLECT) {
            // Double-clic gauche : rassembler les piles identiques de l'inventaire sur le curseur.
            changed = collectToCursor(player, isA);
        } else if (region == REGION_OFFER) {
            changed = clickOffer(player, isA, slot, button, shift);
        } else {
            changed = clickInventory(player, isA, slot, button, shift);
        }
        if (!changed) return;

        // Toute modification réinitialise la confirmation du joueur concerné.
        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        player.updateInventory();
        sendUpdate();
    }

    // ── Clic sur l'inventaire du joueur ───────────────────────────────────────

    private boolean clickInventory(Player player, boolean isA, int slot, int button, boolean shift) {
        if (slot < 0 || slot > 35) return false;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack here   = norm(inv.getItem(slot));
        ItemStack cursor = getCursor(isA);

        if (shift) {
            // Shift-clic depuis l'inventaire → transfert rapide vers l'offre.
            if (here == null) return false;
            ItemStack leftover = moveIntoOffer(isA, here);
            if (leftover != null && leftover.getAmount() == here.getAmount()) return false; // rien n'a bougé
            inv.setItem(slot, leftover);
            return true;
        }

        if (button == 0) { // ── clic gauche ──
            if (cursor == null) {
                if (here == null) return false;
                setCursor(isA, here);
                inv.setItem(slot, null);
                return true;
            }
            if (here == null) {                       // poser toute la pile
                inv.setItem(slot, cursor);
                setCursor(isA, null);
                return true;
            }
            if (here.isSimilar(cursor)) {             // fusionner
                int move = Math.min(cursor.getAmount(), here.getMaxStackSize() - here.getAmount());
                if (move <= 0) { swapInv(inv, slot, isA, here, cursor); return true; }
                here.setAmount(here.getAmount() + move);
                cursor.setAmount(cursor.getAmount() - move);
                inv.setItem(slot, here);
                setCursor(isA, cursor);
                return true;
            }
            swapInv(inv, slot, isA, here, cursor);    // échanger
            return true;
        }

        // ── clic droit ──
        if (cursor == null) {
            if (here == null) return false;
            int half = (here.getAmount() + 1) / 2;    // moitié arrondie au-dessus sur le curseur
            ItemStack take = here.clone(); take.setAmount(half);
            setCursor(isA, take);
            int left = here.getAmount() - half;
            here.setAmount(left);
            inv.setItem(slot, left <= 0 ? null : here);
            return true;
        }
        if (here == null) {                           // déposer une unité
            ItemStack one = cursor.clone(); one.setAmount(1);
            inv.setItem(slot, one);
            decCursor(isA, cursor);
            return true;
        }
        if (here.isSimilar(cursor) && here.getAmount() < here.getMaxStackSize()) {
            here.setAmount(here.getAmount() + 1);
            inv.setItem(slot, here);
            decCursor(isA, cursor);
            return true;
        }
        return false; // items différents au clic droit → no-op (comme vanilla)
    }

    // ── Clic sur l'offre ──────────────────────────────────────────────────────

    private boolean clickOffer(Player player, boolean isA, int slot, int button, boolean shift) {
        if (slot < 0 || slot >= MAX_OFFER) return false;
        ItemStack[] off = isA ? offerA : offerB;
        ItemStack here   = norm(off[slot]);
        ItemStack cursor = getCursor(isA);

        if (shift) {
            // Shift-clic depuis l'offre → reprendre dans l'inventaire.
            if (here == null) return false;
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(here.clone());
            if (leftover.isEmpty()) { off[slot] = null; return true; }
            ItemStack lo = leftover.values().iterator().next();
            if (lo.getAmount() == here.getAmount()) return false; // inventaire plein, rien bougé
            off[slot] = norm(lo);
            return true;
        }

        if (button == 0) { // ── clic gauche ──
            if (cursor == null) {
                if (here == null) return false;
                setCursor(isA, here);
                off[slot] = null;
                return true;
            }
            if (here == null) {
                off[slot] = cursor;
                setCursor(isA, null);
                return true;
            }
            if (here.isSimilar(cursor)) {
                int move = Math.min(cursor.getAmount(), here.getMaxStackSize() - here.getAmount());
                if (move <= 0) { off[slot] = cursor; setCursor(isA, here); return true; }
                here.setAmount(here.getAmount() + move);
                cursor.setAmount(cursor.getAmount() - move);
                off[slot] = here;
                setCursor(isA, cursor);
                return true;
            }
            off[slot] = cursor; setCursor(isA, here);  // échanger
            return true;
        }

        // ── clic droit ──
        if (cursor == null) {
            if (here == null) return false;
            int half = (here.getAmount() + 1) / 2;
            ItemStack take = here.clone(); take.setAmount(half);
            setCursor(isA, take);
            int left = here.getAmount() - half;
            here.setAmount(left);
            off[slot] = left <= 0 ? null : here;
            return true;
        }
        if (here == null) {
            ItemStack one = cursor.clone(); one.setAmount(1);
            off[slot] = one;
            decCursor(isA, cursor);
            return true;
        }
        if (here.isSimilar(cursor) && here.getAmount() < here.getMaxStackSize()) {
            here.setAmount(here.getAmount() + 1);
            off[slot] = here;
            decCursor(isA, cursor);
            return true;
        }
        return false;
    }

    // ── Helpers de clic ────────────────────────────────────────────────────────

    /**
     * Double-clic : rassemble sur le curseur toutes les piles identiques, jusqu'à la taille de
     * pile max. On balaie l'inventaire ET l'offre (comme un inventaire vanilla où tous les slots
     * de la fenêtre comptent). Passe 1 = piles partielles (consolidation), passe 2 = piles pleines.
     */
    private boolean collectToCursor(Player player, boolean isA) {
        ItemStack cursor = getCursor(isA);
        if (cursor == null) return false;
        int max = cursor.getMaxStackSize();
        if (max <= 1 || cursor.getAmount() >= max) return false;

        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack[] off = isA ? offerA : offerB;
        boolean changed = false;
        for (int pass = 0; pass < 2 && cursor.getAmount() < max; pass++) {
            boolean partialsOnly = (pass == 0);
            // 1) Inventaire du joueur
            for (int slot = 0; slot <= 35 && cursor.getAmount() < max; slot++) {
                ItemStack here = norm(inv.getItem(slot));
                if (!gatherable(here, cursor, partialsOnly)) continue;
                int move = Math.min(max - cursor.getAmount(), here.getAmount());
                cursor.setAmount(cursor.getAmount() + move);
                here.setAmount(here.getAmount() - move);
                inv.setItem(slot, here.getAmount() <= 0 ? null : here);
                changed = true;
            }
            // 2) Slots d'offre
            for (int i = 0; i < MAX_OFFER && cursor.getAmount() < max; i++) {
                ItemStack here = norm(off[i]);
                if (!gatherable(here, cursor, partialsOnly)) continue;
                int move = Math.min(max - cursor.getAmount(), here.getAmount());
                cursor.setAmount(cursor.getAmount() + move);
                here.setAmount(here.getAmount() - move);
                off[i] = here.getAmount() <= 0 ? null : here;
                changed = true;
            }
        }
        if (changed) setCursor(isA, cursor);
        return changed;
    }

    /** Un slot est-il à ramasser pour la collecte (même item ; phase partielle/pleine) ? */
    private static boolean gatherable(ItemStack here, ItemStack cursor, boolean partialsOnly) {
        if (here == null || !here.isSimilar(cursor)) return false;
        boolean full = here.getAmount() >= here.getMaxStackSize();
        return partialsOnly != full; // passe partielle → non plein ; passe pleine → plein
    }

    /** Fusionne/insère {@code stack} dans l'offre. Retourne le reste non placé (ou null). */
    private ItemStack moveIntoOffer(boolean isA, ItemStack stack) {
        ItemStack[] off = isA ? offerA : offerB;
        ItemStack rem = stack.clone();
        for (int i = 0; i < MAX_OFFER && rem != null; i++) {
            if (off[i] != null && off[i].isSimilar(rem)) {
                int move = Math.min(rem.getAmount(), off[i].getMaxStackSize() - off[i].getAmount());
                if (move > 0) {
                    off[i].setAmount(off[i].getAmount() + move);
                    rem.setAmount(rem.getAmount() - move);
                    if (rem.getAmount() <= 0) rem = null;
                }
            }
        }
        for (int i = 0; i < MAX_OFFER && rem != null; i++) {
            if (off[i] == null) { off[i] = rem; rem = null; }
        }
        return rem;
    }

    private void swapInv(org.bukkit.inventory.PlayerInventory inv, int slot, boolean isA,
                         ItemStack here, ItemStack cursor) {
        inv.setItem(slot, cursor);
        setCursor(isA, here);
    }

    private ItemStack getCursor(boolean isA) { return isA ? cursorA : cursorB; }

    private void setCursor(boolean isA, ItemStack s) {
        s = norm(s);
        if (isA) cursorA = s; else cursorB = s;
    }

    /** Décrémente le curseur d'une unité (le vide si épuisé). */
    private void decCursor(boolean isA, ItemStack cursor) {
        cursor.setAmount(cursor.getAmount() - 1);
        setCursor(isA, cursor);
    }

    private static ItemStack norm(ItemStack item) {
        return (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) ? null : item;
    }

    /**
     * Fixe le montant d'argent offert par ce joueur. Plafonné par son solde Vault.
     * Toute modification réinitialise sa confirmation.
     */
    public void setMoneyOffer(Player player, long amount) {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        boolean isA = playerA.equals(player);
        boolean isB = playerB.equals(player);
        if (!isA && !isB) return;

        // Plafonnement par le solde Vault disponible
        Economy econ = VaultEconomy.get();
        long cap = Long.MAX_VALUE;
        if (econ != null) {
            try { cap = (long) econ.getBalance(player); } catch (Exception ignored) {}
        }
        long capped = Math.max(0L, Math.min(amount, cap));

        long current = isA ? moneyA : moneyB;
        if (current == capped) return; // no change

        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        if (isA) moneyA = capped; else moneyB = capped;
        sendUpdate();
    }

    /**
     * Fixe le montant de PB offert par ce joueur. Plafonné par son solde PB.
     * Toute modification réinitialise sa confirmation.
     */
    public void setPBOffer(Player player, int amount) {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        boolean isA = playerA.equals(player);
        boolean isB = playerB.equals(player);
        if (!isA && !isB) return;

        int cap = Integer.MAX_VALUE;
        try {
            if (RedConflictCore.getInstance().getPBManager() != null) {
                cap = RedConflictCore.getInstance().getPBManager().get(player);
            }
        } catch (Exception ignored) {}
        int capped = Math.max(0, Math.min(amount, cap));

        int current = isA ? pbA : pbB;
        if (current == capped) return;

        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        if (isA) pbA = capped; else pbB = capped;
        sendUpdate();
    }

    public void takeBackFromOffer(Player player, int index) {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        boolean isA = playerA.equals(player);
        boolean isB = playerB.equals(player);
        if (!isA && !isB) return;

        ItemStack[] myOffer = isA ? offerA : offerB;
        if (index < 0 || index >= MAX_OFFER || myOffer[index] == null) return;

        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        ItemStack item = myOffer[index];
        myOffer[index] = null;
        giveItem(player, item);
        sendUpdate();
    }

    // ── Confirm / Cancel ─────────────────────────────────────────────────────

    public void confirmA() {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        if (state == State.CONFIRMED_B) {
            state = State.DONE;
            executeTrade();
        } else {
            state = State.CONFIRMED_A;
            sendUpdate();
        }
    }

    public void confirmB() {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        if (state == State.CONFIRMED_A) {
            state = State.DONE;
            executeTrade();
        } else {
            state = State.CONFIRMED_B;
            sendUpdate();
        }
    }

    public void cancel(Player who) {
        if (state == State.DONE || state == State.CANCELLED) return;
        state = State.CANCELLED;
        returnItems();
        String name = who.getName();
        playerA.sendMessage(RC.PRE + "§cTrade annulé par §f" + name + "§c.");
        playerB.sendMessage(RC.PRE + "§cTrade annulé par §f" + name + "§c.");
        TradePacketSender.sendClose(playerA, false);
        TradePacketSender.sendClose(playerB, false);
    }

    public void onPlayerQuit(Player who) {
        cancel(who);
    }

    // ── Execution ────────────────────────────────────────────────────────────

    private void executeTrade() {
        executing = true;

        // ── Re-validation des montants vs solde courant (anti-cheat) ─────────
        Economy econ = VaultEconomy.get();
        if (econ != null) {
            if (moneyA > 0 && econ.getBalance(playerA) < moneyA) { abortInsufficient(playerA); return; }
            if (moneyB > 0 && econ.getBalance(playerB) < moneyB) { abortInsufficient(playerB); return; }
        }
        fr.redconflict.pb.PBManager pbm = RedConflictCore.getInstance().getPBManager();
        if (pbm != null) {
            if (pbA > 0 && pbm.get(playerA) < pbA) { abortInsufficientPB(playerA); return; }
            if (pbB > 0 && pbm.get(playerB) < pbB) { abortInsufficientPB(playerB); return; }
        }

        // Restituer d'abord aux joueurs les items portés sur le curseur (en transit).
        returnCursors();

        List<ItemStack> snapA = listOf(offerA);
        List<ItemStack> snapB = listOf(offerB);
        long mA = moneyA, mB = moneyB;
        int  ppA = pbA,    ppB = pbB;
        clearOffer(offerA); clearOffer(offerB);
        moneyA = 0L; moneyB = 0L;
        pbA = 0; pbB = 0;

        // ── Transfert argent ────────────────────────────────────────────────
        if (econ != null) {
            if (mA > 0) { econ.withdrawPlayer(playerA, mA); econ.depositPlayer(playerB, mA); }
            if (mB > 0) { econ.withdrawPlayer(playerB, mB); econ.depositPlayer(playerA, mB); }
        }

        // ── Transfert PB (atomique avec rollback) ───────────────────────────
        if (pbm != null) {
            boolean rolledBack = false;
            if (ppA > 0) {
                if (!pbm.remove(playerA, ppA, "TRADE_OUT:" + playerB.getName())) { rolledBack = true; }
                else if (!pbm.add(playerB, ppA, "TRADE_IN:" + playerA.getName())) {
                    pbm.add(playerA, ppA, "TRADE_ROLLBACK");
                    rolledBack = true;
                }
            }
            if (!rolledBack && ppB > 0) {
                if (!pbm.remove(playerB, ppB, "TRADE_OUT:" + playerA.getName())) {
                    if (ppA > 0) {
                        // restitution flux A→B
                        pbm.remove(playerB, ppA, "TRADE_ROLLBACK_B");
                        pbm.add(playerA, ppA, "TRADE_ROLLBACK_A");
                    }
                    rolledBack = true;
                } else if (!pbm.add(playerA, ppB, "TRADE_IN:" + playerB.getName())) {
                    pbm.add(playerB, ppB, "TRADE_ROLLBACK_B2");
                    if (ppA > 0) {
                        pbm.remove(playerB, ppA, "TRADE_ROLLBACK_B");
                        pbm.add(playerA, ppA, "TRADE_ROLLBACK_A");
                    }
                    rolledBack = true;
                }
            }
            if (rolledBack) {
                // Rollback argent
                if (econ != null) {
                    if (mA > 0) { econ.withdrawPlayer(playerB, mA); econ.depositPlayer(playerA, mA); }
                    if (mB > 0) { econ.withdrawPlayer(playerA, mB); econ.depositPlayer(playerB, mB); }
                }
                state = State.CANCELLED;
                restoreOffer(offerA, snapA); restoreOffer(offerB, snapB);
                returnItems();
                playerA.sendMessage(RC.PRE + "§cÉchange annulé : transfert PB impossible.");
                playerB.sendMessage(RC.PRE + "§cÉchange annulé : transfert PB impossible.");
                TradePacketSender.sendClose(playerA, false);
                TradePacketSender.sendClose(playerB, false);
                logTradeFile("FAIL_PB", snapA, snapB, mA, mB, ppA, ppB);
                return;
            }
        }

        // ── Transfert items ─────────────────────────────────────────────────
        giveItems(playerA, snapB);
        giveItems(playerB, snapA);

        String summaryA = buildSummary(snapB, mB, ppB);
        String summaryB = buildSummary(snapA, mA, ppA);
        playerA.sendMessage(RC.PRE + "§aÉchange effectué avec §f" + playerB.getName() + "§a. §7Reçu : " + summaryA);
        playerB.sendMessage(RC.PRE + "§aÉchange effectué avec §f" + playerA.getName() + "§a. §7Reçu : " + summaryB);
        TradePacketSender.sendClose(playerA, true);
        TradePacketSender.sendClose(playerB, true);

        // Pousser les soldes PB fraîchement mis à jour aux deux clients
        try {
            int newPbA = pbm != null ? pbm.get(playerA) : 0;
            int newPbB = pbm != null ? pbm.get(playerB) : 0;
            fr.redconflict.data.PlayerDataServerHandler.sendPB(playerA, newPbA);
            fr.redconflict.data.PlayerDataServerHandler.sendPB(playerB, newPbB);
        } catch (Exception ignored) {}

        logTradeFile("SUCCESS", snapA, snapB, mA, mB, ppA, ppB);
    }

    private void abortInsufficientPB(Player culprit) {
        state = State.CANCELLED;
        returnItems();
        playerA.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus assez de PB.");
        playerB.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus assez de PB.");
        TradePacketSender.sendClose(playerA, false);
        TradePacketSender.sendClose(playerB, false);
    }

    /** Append-only trade_logs.txt — concis et lisible. */
    private void logTradeFile(String status, List<ItemStack> snapA, List<ItemStack> snapB,
                              long mA, long mB, int ppA, int ppB) {
        try {
            java.io.File f = new java.io.File(RedConflictCore.getInstance().getDataFolder(), "social/trade_logs.txt");
            f.getParentFile().mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
                w.write("[" + ts + "] " + status + " | " + playerA.getName() + " <-> " + playerB.getName()
                        + " | $A=" + mA + " $B=" + mB + " | PBA=" + ppA + " PBB=" + ppB
                        + " | itemsA=" + summarizeItems(snapA) + " | itemsB=" + summarizeItems(snapB) + "\n");
            }
        } catch (Exception ignored) {}
    }

    private static String summarizeItems(List<ItemStack> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (i > 0) sb.append(",");
            if (s == null) sb.append("null");
            else sb.append(s.getType().name()).append("x").append(s.getAmount());
        }
        return sb.append("]").toString();
    }

    private void abortInsufficient(Player culprit) {
        state = State.CANCELLED;
        returnItems();
        playerA.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus les fonds nécessaires.");
        playerB.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus les fonds nécessaires.");
        TradePacketSender.sendClose(playerA, false);
        TradePacketSender.sendClose(playerB, false);
    }

    private static String buildSummary(List<ItemStack> items, long money, int pb) {
        StringBuilder sb = new StringBuilder();
        if (!items.isEmpty()) sb.append(items.size()).append(" item(s)");
        if (money > 0) { if (sb.length() > 0) sb.append(" + "); sb.append("§e").append(money).append("$"); }
        if (pb > 0)    { if (sb.length() > 0) sb.append(" + "); sb.append("§e").append(pb).append(" PB"); }
        if (sb.length() == 0) sb.append("rien");
        return sb.toString();
    }

    private void returnItems() {
        List<ItemStack> snapA = listOf(offerA);
        List<ItemStack> snapB = listOf(offerB);
        clearOffer(offerA);
        clearOffer(offerB);
        giveItems(playerA, snapA);
        giveItems(playerB, snapB);
        returnCursors();
    }

    /** Rend à chaque joueur l'item qu'il portait sur le curseur, puis vide les curseurs. */
    private void returnCursors() {
        if (cursorA != null) { giveItem(playerA, cursorA); cursorA = null; }
        if (cursorB != null) { giveItem(playerB, cursorB); cursorB = null; }
        playerA.updateInventory();
        playerB.updateInventory();
    }

    // ── Helpers tableau d'offre ────────────────────────────────────────────────

    /** Liste des items non nuls de l'offre (ordre des slots). */
    private static List<ItemStack> listOf(ItemStack[] offer) {
        List<ItemStack> out = new ArrayList<>(MAX_OFFER);
        for (ItemStack it : offer) if (it != null) out.add(it);
        return out;
    }

    private static void clearOffer(ItemStack[] offer) {
        for (int i = 0; i < offer.length; i++) offer[i] = null;
    }

    /** Remplit l'offre depuis une liste (slots séquentiels) — utilisé pour le rollback. */
    private static void restoreOffer(ItemStack[] offer, List<ItemStack> items) {
        clearOffer(offer);
        for (int i = 0; i < items.size() && i < offer.length; i++) offer[i] = items.get(i);
    }

    private void giveItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) giveItem(player, item);
    }

    private void giveItem(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
            player.sendMessage(ChatColor.YELLOW + "⚠ Item droppé : inventaire plein.");
        }
        player.updateInventory();
    }

    // ── Packet sending ────────────────────────────────────────────────────────

    private void sendUpdate() {
        boolean aConf = state == State.CONFIRMED_A || state == State.DONE;
        boolean bConf = state == State.CONFIRMED_B || state == State.DONE;
        // On envoie les 15 slots à positions fixes (nulls inclus) + le curseur propre au joueur.
        List<ItemStack> a = java.util.Arrays.asList(offerA);
        List<ItemStack> b = java.util.Arrays.asList(offerB);
        TradePacketSender.sendUpdate(playerA, a, b, aConf, bConf, moneyA, moneyB, pbA, pbB, cursorA);
        TradePacketSender.sendUpdate(playerB, b, a, bConf, aConf, moneyB, moneyA, pbB, pbA, cursorB);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Player getPlayerA() { return playerA; }
    public Player getPlayerB() { return playerB; }
    public boolean isActive()  { return state != State.CANCELLED && state != State.DONE; }
}
