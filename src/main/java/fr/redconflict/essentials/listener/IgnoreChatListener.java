package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.service.IgnoreService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Iterator;

/**
 * Retire des destinataires du chat public les joueurs qui ignorent l'émetteur.
 * Événement asynchrone : {@link IgnoreService#isIgnoring} ne lit que le cache
 * mémoire (thread-safe), jamais la base.
 */
public class IgnoreChatListener implements Listener {

    private final IgnoreService ignores;

    public IgnoreChatListener(IgnoreService ignores) {
        this.ignores = ignores;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Iterator<Player> recipients = event.getRecipients().iterator();
        while (recipients.hasNext()) {
            Player recipient = recipients.next();
            if (ignores.isIgnoring(recipient.getUniqueId(), event.getPlayer().getUniqueId())) {
                recipients.remove();
            }
        }
    }
}
