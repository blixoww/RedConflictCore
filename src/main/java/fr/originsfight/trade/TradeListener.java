package fr.originsfight.trade;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Listener de l'inventaire de trade.
 * Gère les clics, la confirmation, l'annulation et la protection anti-dupe.
 */
public class TradeListener implements Listener {

    private final TradeManager manager = TradeManager.getInstance();

    // ── Clic dans l'inventaire ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        TradeSession session = manager.getSession(player);
        if (session == null || !session.isActive()) return;

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        int slot = event.getSlot();
        boolean isTradeInv = clickedInv.equals(session.getInventory());

        // Si le joueur clique dans son propre inventaire (en bas) → laisser passer normalement
        // SAUF les shift-clics qui enverraient des items dans la mauvaise zone
        if (!isTradeInv) {
            if (event.isShiftClick()) {
                // On permet le shift-click pour envoyer dans la bonne zone
                handleShiftClickFromPlayer(event, player, session);
            }
            return;
        }

        // Clic dans l'inventaire de trade
        event.setCancelled(true); // Par défaut on annule, on gère manuellement

        boolean isA = session.getPlayerA().equals(player);
        boolean isB = session.getPlayerB().equals(player);

        // Bouton Confirmer
        if (slot == TradeSession.SLOT_CONFIRM_A && isA) {
            session.confirmA();
            return;
        }
        if (slot == TradeSession.SLOT_CONFIRM_B && isB) {
            session.confirmB();
            return;
        }

        // Bouton Annuler
        if ((slot == TradeSession.SLOT_CANCEL_A && isA) || (slot == TradeSession.SLOT_CANCEL_B && isB)) {
            manager.removeSession(session);
            session.cancel(player);
            return;
        }

        // Slots séparateurs / status → bloqué
        if (TradeSession.SEPARATOR_SLOTS.contains(slot)
                || slot == TradeSession.SLOT_STATUS_A
                || slot == TradeSession.SLOT_STATUS_B) {
            return;
        }

        // Si joueur A clique dans sa zone
        if (isA && TradeSession.OFFER_SLOTS_A.contains(slot)) {
            handleItemPlacement(event, player, session, slot, true);
            return;
        }

        // Si joueur B clique dans sa zone
        if (isB && TradeSession.OFFER_SLOTS_B.contains(slot)) {
            handleItemPlacement(event, player, session, slot, false);
            return;
        }

        // Toute autre zone (zone de l'autre joueur) → bloquée
    }

    private void handleItemPlacement(InventoryClickEvent event, Player player, TradeSession session, int slot, boolean isA) {
        // Si le joueur avait confirmé, retirer sa confirmation (il modifie son offre)
        if (isA) session.unconfirmA(); else session.unconfirmB();

        // Laisser le clic se faire normalement (on re-autorise)
        event.setCancelled(false);
    }

    private void handleShiftClickFromPlayer(InventoryClickEvent event, Player player, TradeSession session) {
        boolean isA = session.getPlayerA().equals(player);
        boolean isB = session.getPlayerB().equals(player);
        if (!isA && !isB) return;

        ItemStack cursor = event.getCurrentItem();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        event.setCancelled(true);

        // Trouver un slot libre dans la bonne zone
        java.util.Set<Integer> zone = isA ? TradeSession.OFFER_SLOTS_A : TradeSession.OFFER_SLOTS_B;
        for (int slot : zone) {
            ItemStack existing = session.getInventory().getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                session.getInventory().setItem(slot, cursor.clone());
                event.setCurrentItem(null);
                // Retirer la confirmation si modifié
                if (isA) session.unconfirmA(); else session.unconfirmB();
                break;
            }
        }
    }

    // ── Drag dans l'inventaire ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        TradeSession session = manager.getSession(player);
        if (session == null || !session.isActive()) return;

        boolean isA = session.getPlayerA().equals(player);
        java.util.Set<Integer> allowedZone = isA ? TradeSession.OFFER_SLOTS_A : TradeSession.OFFER_SLOTS_B;

        for (int slot : event.getRawSlots()) {
            // Si le drag touche un slot interdit → on annule tout
            if (slot < 54 && !allowedZone.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }

        // Drag dans la zone autorisée → annuler la confirmation
        if (isA) session.unconfirmA(); else session.unconfirmB();
    }

    // ── Fermeture de l'inventaire ─────────────────────────────────────────────

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        TradeSession session = manager.getSession(player);
        if (session == null || !session.isActive()) return;

        // Si le trade est terminé ou annulé, ne rien faire
        if (!session.isActive()) return;

        // Si l'inventaire fermé est celui du trade (pas une ré-ouverture)
        if (event.getInventory().equals(session.getInventory())) {
            manager.removeSession(session);
            session.onClose(player);
        }
    }

    // ── Déconnexion ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        TradeSession session = manager.getSession(player);
        if (session != null && session.isActive()) {
            manager.removeSession(session);
            session.cancel(player);
        }
        manager.cleanupPlayer(player);
    }
}


