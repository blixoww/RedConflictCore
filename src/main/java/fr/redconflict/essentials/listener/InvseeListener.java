package fr.redconflict.essentials.listener;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.service.InvseeSessions;
import fr.redconflict.essentials.service.InvseeView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Tient à jour les fenêtres ouvertes par le staff sur l'inventaire d'un autre
 * joueur, et tranche ce qu'un clic y est autorisé à faire.
 *
 * <p><b>Trois responsabilités, toutes anti-duplication.</b>
 *
 * <p>1. <b>Le miroir de {@code /invsee} est modifiable</b>, et ses gestes sont
 * transmis à l'observé au tick suivant plutôt qu'à la seule fermeture : le staff
 * voit son effet tout de suite, et un plantage du serveur entre-temps ne perd
 * qu'un geste. Les cases de séparation, elles, n'ont rien à donner : les clics
 * dessus sont annulés, sinon on distribue des vitres.
 *
 * <p>2. <b>L'instantané de {@code /ec} est en lecture seule</b>, et les clics y
 * sont annulés. Ce qui s'y affiche est une copie relue en base : les objets
 * qu'on en sortirait n'appartiennent à personne, ils seraient créés de rien.
 *
 * <p>3. <b>L'observé qui s'en va fait fermer les fenêtres.</b> Une fenêtre
 * ouverte sur un joueur parti manipule un inventaire qui n'appartient plus à
 * personne : ce qu'on y dépose disparaît à sa prochaine sauvegarde, ce qu'on en
 * sort existe pour de bon. C'est le chemin classique de la duplication.
 */
public class InvseeListener implements Listener {

    private final JavaPlugin plugin;
    private final InvseeSessions sessions;

    public InvseeListener(JavaPlugin plugin, InvseeSessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * Un clic dans la fenêtre : annulé si la copie est en lecture seule ou si la
     * case n'est qu'un séparateur, transmis à l'observé sinon.
     *
     * <p>La transmission attend le tick suivant, et ce n'est pas un détail :
     * l'événement arrive <b>avant</b> que le clic ne soit appliqué à
     * l'inventaire. Lire le miroir ici rendrait l'état d'avant le clic.
     */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        UUID viewer = event.getWhoClicked().getUniqueId();
        if (!sessions.isViewing(viewer)) return;

        if (sessions.isReadOnly(viewer)) {
            event.setCancelled(true);
            return;
        }

        final InvseeView mirror = sessions.mirrorOf(viewer);
        if (mirror == null) return;

        // getRawSlot désigne la fenêtre du haut tant qu'il est sous sa taille ;
        // au-delà, le joueur clique dans son propre inventaire, ce qui ne
        // concerne pas l'observé.
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < InvseeView.SIZE && InvseeView.isFiller(raw)) {
            event.setCancelled(true);
            return;
        }

        pushNextTick(mirror);
    }

    /** Même règle pour un glissé, qui touche plusieurs cases d'un coup. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        UUID viewer = event.getWhoClicked().getUniqueId();
        if (!sessions.isViewing(viewer)) return;

        if (sessions.isReadOnly(viewer)) {
            event.setCancelled(true);
            return;
        }

        InvseeView mirror = sessions.mirrorOf(viewer);
        if (mirror == null) return;

        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < InvseeView.SIZE && InvseeView.isFiller(raw)) {
                event.setCancelled(true);
                return;
            }
        }

        pushNextTick(mirror);
    }

    /** Transmet à l'observé les cases que le staff vient de modifier. */
    private void pushNextTick(final InvseeView mirror) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (mirror.isDead()) return;
                mirror.apply(Bukkit.getPlayer(mirror.target()));
            }
        });
    }

    /** La fermeture transmet une dernière fois, puis oublie la session. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        sessions.close(event.getPlayer().getUniqueId());
    }

    /**
     * L'observé s'en va : on ferme toutes les fenêtres ouvertes sur lui.
     *
     * <p>Priorité la plus basse pour passer avant les sauvegardes d'inventaire
     * (le module de synchronisation écrit à MONITOR). Dans cet ordre, les gestes
     * du staff encore en attente lui sont transmis <b>pendant qu'il est là</b>,
     * et sont donc sauvegardés avec le reste ; ensuite seulement les miroirs sont
     * condamnés, et la fermeture rend au staff ce qui traîne dans son curseur.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID quitting = event.getPlayer().getUniqueId();

        sessions.retireTarget(event.getPlayer());
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
