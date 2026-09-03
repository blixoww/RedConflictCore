package fr.redconflict.essentials.listener;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.service.InvseeSessions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Tient à jour les sessions /invsee, et ferme celles dont l'observé s'en va.
 *
 * <p><b>L'inventaire est modifiable.</b> Les clics de l'observateur étaient
 * auparavant annulés ici : le staff pouvait regarder, pas retirer un objet dupé
 * ni rendre un objet perdu. Ils ne le sont plus.
 *
 * <p><b>Ce qui reste indispensable, c'est la fermeture à la déconnexion.</b> Une
 * fenêtre ouverte sur un joueur parti manipule un inventaire qui n'appartient
 * plus à personne : ce qu'on y dépose disparaît à la prochaine sauvegarde du
 * déconnecté, et ce qu'on en sort existe pour de bon. C'est le chemin classique
 * de duplication d'un /invsee modifiable, et la seule raison d'être de ce
 * listener.
 */
public class InvseeListener implements Listener {

    private final InvseeSessions sessions;

    public InvseeListener(InvseeSessions sessions) {
        this.sessions = sessions;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        sessions.close(event.getPlayer().getUniqueId());
    }

    /**
     * L'observé s'en va : on ferme toutes les fenêtres ouvertes sur lui.
     *
     * <p>Priorité la plus basse pour passer avant les sauvegardes d'inventaire
     * (le module de synchronisation écrit à MONITOR) : la fermeture rend d'abord
     * ce qui est dans le curseur de l'observateur, sinon cet objet-là serait
     * écrit dans l'instantané... et rendu à l'observateur en même temps.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID quitting = event.getPlayer().getUniqueId();
        sessions.close(quitting); // l'observateur lui-même s'en va

        for (UUID viewerId : sessions.viewersOf(quitting)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            sessions.close(viewerId);
            if (viewer != null && viewer.isOnline()) {
                viewer.closeInventory();
                viewer.sendMessage(Text.info("§f" + event.getPlayer().getName()
                        + " §7s'est déconnecté : son inventaire a été fermé."));
            }
        }
    }
}
