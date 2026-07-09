package fr.redconflict.ring;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Gère le comportement des anneaux/colliers lors de la mort d'un joueur.
 *
 * Comportement par défaut (sans totem) :
 *   Les anneaux/colliers équipés sont droppés à l'emplacement de la mort et
 *   les slots ring sont vidés.
 *
 * Avec le Totem of Undying équipé dans un slot ring :
 *   Tous les anneaux/colliers sont conservés dans le GUI — aucun drop.
 *   Le totem lui-même est consommé (retiré de son slot) après activation.
 *
 * keepInventory activé :
 *   Les anneaux ne sont jamais droppés (comportement cohérent avec l'inventaire normal).
 */
public class RingDeathListener implements Listener {

    private final RingPacketSender sender;

    public RingDeathListener(RingPacketSender sender) {
        this.sender = sender;
    }

    /**
     * Intercepte la mort du joueur AVANT les autres listeners
     * (EventPriority.HIGH) pour modifier les drops proprement.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        RingManager manager = RingEffects.getManager();
        if (manager == null) return;

        // Si keepInventory est actif, les rings restent équipés — rien à faire.
        if (event.getKeepInventory()) return;

        // Si le joueur a le Totem of Undying dans le GUI → les rings/colliers sont préservés.
        if (RingEffects.hasRing(player, RingEffects.TOTEM_OF_UNDYING)) {
            sender.sendSync(player);
            return;
        }

        // Sinon : dropper tous les rings/colliers équipés et vider les slots.
        ItemStack[] slots = manager.getSlots(uuid);

        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                event.getDrops().add(slots[i].clone());
                manager.clearSlot(uuid, i);
            }
        }

        // Forcer une sauvegarde immédiate après nettoyage des slots.
        manager.savePlayer(uuid);
    }
}
