package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Charge l'avancement à la connexion, l'écrit à la déconnexion, et pousse le
 * catalogue vers le client moddé.
 *
 * <p>L'envoi est <b>différé d'une seconde</b> : le canal du client n'est
 * enregistré qu'une fois la connexion complètement établie, et un paquet
 * envoyé trop tôt part dans le vide.
 */
public class SuccesLoginListener implements Listener {

    private final RedConflictCore plugin;
    private final SuccesManager manager;
    private final SuccesPacketSender sender;
    private final SuccesGameplayListener gameplay;

    public SuccesLoginListener(RedConflictCore plugin, SuccesManager manager,
                               SuccesPacketSender sender, SuccesGameplayListener gameplay) {
        this.plugin = plugin;
        this.manager = manager;
        this.sender = sender;
        this.gameplay = gameplay;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        manager.load(player);

        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                sender.sendInit(player);
                sender.sendData(player);

                manager.refreshPlaytime(java.util.Collections.singletonList(player.getUniqueId()));

                int pending = manager.pendingCount(player.getUniqueId());
                if (pending > 0) {
                    player.sendMessage("§6✦ §7Vous avez §e" + pending
                            + " §7récompense(s) de succès à récupérer §8— §e/succes");
                }
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        gameplay.forget(event.getPlayer().getUniqueId());
        manager.unload(event.getPlayer().getUniqueId());
    }
}
