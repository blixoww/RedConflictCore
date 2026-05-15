package fr.originsfight.trade;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
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

    private final Player playerA;
    private final Player playerB;
    private final List<ItemStack> offerA = new ArrayList<>(MAX_OFFER);
    private final List<ItemStack> offerB = new ArrayList<>(MAX_OFFER);
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

    public void offerFromInventory(Player player, int invSlot) {
        if (executing || state == State.DONE || state == State.CANCELLED) return;
        boolean isA = playerA.equals(player);
        boolean isB = playerB.equals(player);
        if (!isA && !isB) return;

        List<ItemStack> myOffer = isA ? offerA : offerB;
        if (myOffer.size() >= MAX_OFFER) return;

        ItemStack item = player.getInventory().getItem(invSlot);
        if (item == null || item.getType() == Material.AIR) return;

        // unconfirm if needed
        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        myOffer.add(item.clone());
        player.getInventory().setItem(invSlot, null);
        player.updateInventory();
        sendUpdate();
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
        Economy econ = OriginsFightCore.getInstance().getEconomy();
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
            if (OriginsFightCore.getInstance().getPBManager() != null) {
                cap = OriginsFightCore.getInstance().getPBManager().get(player);
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

        List<ItemStack> myOffer = isA ? offerA : offerB;
        if (index < 0 || index >= myOffer.size()) return;

        if (isA && state == State.CONFIRMED_A) state = State.WAITING;
        if (isB && state == State.CONFIRMED_B) state = State.WAITING;

        ItemStack item = myOffer.remove(index);
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
        Economy econ = OriginsFightCore.getInstance().getEconomy();
        if (econ != null) {
            if (moneyA > 0 && econ.getBalance(playerA) < moneyA) { abortInsufficient(playerA); return; }
            if (moneyB > 0 && econ.getBalance(playerB) < moneyB) { abortInsufficient(playerB); return; }
        }
        fr.originsfight.pb.PBManager pbm = OriginsFightCore.getInstance().getPBManager();
        if (pbm != null) {
            if (pbA > 0 && pbm.get(playerA) < pbA) { abortInsufficientPB(playerA); return; }
            if (pbB > 0 && pbm.get(playerB) < pbB) { abortInsufficientPB(playerB); return; }
        }

        List<ItemStack> snapA = new ArrayList<>(offerA);
        List<ItemStack> snapB = new ArrayList<>(offerB);
        long mA = moneyA, mB = moneyB;
        int  ppA = pbA,    ppB = pbB;
        offerA.clear(); offerB.clear();
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
                offerA.addAll(snapA); offerB.addAll(snapB);
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
            fr.originsfight.data.PlayerDataServerHandler.sendPB(playerA, newPbA);
            fr.originsfight.data.PlayerDataServerHandler.sendPB(playerB, newPbB);
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
            java.io.File f = new java.io.File(OriginsFightCore.getInstance().getDataFolder(), "trade_logs.txt");
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
        List<ItemStack> snapA = new ArrayList<>(offerA);
        List<ItemStack> snapB = new ArrayList<>(offerB);
        offerA.clear();
        offerB.clear();
        giveItems(playerA, snapA);
        giveItems(playerB, snapB);
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
        TradePacketSender.sendUpdate(playerA, offerA, offerB, aConf, bConf, moneyA, moneyB, pbA, pbB);
        TradePacketSender.sendUpdate(playerB, offerB, offerA, bConf, aConf, moneyB, moneyA, pbB, pbA);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Player getPlayerA() { return playerA; }
    public Player getPlayerB() { return playerB; }
    public boolean isActive()  { return state != State.CANCELLED && state != State.DONE; }
}
