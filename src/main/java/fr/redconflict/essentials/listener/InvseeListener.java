package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.service.InvseeSessions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Rend les sessions /invsee en lecture seule : tout clic ou glisser-déposer
 * de l'observateur est annulé, et la session se ferme avec l'inventaire.
 */
public class InvseeListener implements Listener {

    private final InvseeSessions sessions;

    public InvseeListener(InvseeSessions sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (sessions.isViewing(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (sessions.isViewing(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        sessions.close(event.getPlayer().getUniqueId());
    }
}
