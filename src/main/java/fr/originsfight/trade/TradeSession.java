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
            if (moneyA > 0 && econ.getBalance(playerA) < moneyA) {
                abortInsufficient(playerA);
                return;
            }
            if (moneyB > 0 && econ.getBalance(playerB) < moneyB) {
                abortInsufficient(playerB);
                return;
            }
        }

        List<ItemStack> snapA = new ArrayList<>(offerA);
        List<ItemStack> snapB = new ArrayList<>(offerB);
        long mA = moneyA, mB = moneyB;
        offerA.clear();
        offerB.clear();
        moneyA = 0L;
        moneyB = 0L;

        // ── Transfert argent (A → B et B → A) ───────────────────────────────
        if (econ != null) {
            if (mA > 0) {
                econ.withdrawPlayer(playerA, mA);
                econ.depositPlayer(playerB, mA);
            }
            if (mB > 0) {
                econ.withdrawPlayer(playerB, mB);
                econ.depositPlayer(playerA, mB);
            }
        }

        // ── Transfert items ─────────────────────────────────────────────────
        giveItems(playerA, snapB);
        giveItems(playerB, snapA);

        String summaryA = buildSummary(snapB, mB);
        String summaryB = buildSummary(snapA, mA);
        playerA.sendMessage(RC.PRE + "§aÉchange effectué avec §f" + playerB.getName() + "§a. §7Reçu : " + summaryA);
        playerB.sendMessage(RC.PRE + "§aÉchange effectué avec §f" + playerA.getName() + "§a. §7Reçu : " + summaryB);
        TradePacketSender.sendClose(playerA, true);
        TradePacketSender.sendClose(playerB, true);
    }

    private void abortInsufficient(Player culprit) {
        state = State.CANCELLED;
        returnItems();
        playerA.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus les fonds nécessaires.");
        playerB.sendMessage(RC.PRE + "§cÉchange annulé : §f" + culprit.getName() + " §cn'a plus les fonds nécessaires.");
        TradePacketSender.sendClose(playerA, false);
        TradePacketSender.sendClose(playerB, false);
    }

    private static String buildSummary(List<ItemStack> items, long money) {
        StringBuilder sb = new StringBuilder();
        if (!items.isEmpty()) sb.append(items.size()).append(" item(s)");
        if (money > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("§e").append(money).append("$");
        }
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
        TradePacketSender.sendUpdate(playerA, offerA, offerB, aConf, bConf, moneyA, moneyB);
        TradePacketSender.sendUpdate(playerB, offerB, offerA, bConf, aConf, moneyB, moneyA);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Player getPlayerA() { return playerA; }
    public Player getPlayerB() { return playerB; }
    public boolean isActive()  { return state != State.CANCELLED && state != State.DONE; }
}
