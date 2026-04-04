package fr.originsfight.friend;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listener pour le système d'amis.
 *
 * - Annule les dégâts entre deux joueurs qui sont amis.
 * - Sauvegarde le nom du joueur à la connexion.
 */
public class FriendListener implements Listener {

    private final FriendManager manager;

    public FriendListener(FriendManager manager) {
        this.manager = manager;
    }

    // ── Blocage des dégâts entre amis ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getDamager() instanceof Player)) return;

        Player victim  = (Player) event.getEntity();
        Player damager = (Player) event.getDamager();

        if (manager.areFriends(damager.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
            damager.sendMessage(fr.originsfight.RC.PRE + "§7Vous ne pouvez pas attaquer §f" + victim.getName() + " §7(ami).");
        }
    }

    // ── Sauvegarde du nom à la connexion ──────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        manager.saveName(p.getUniqueId(), p.getName());
    }
}

