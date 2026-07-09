package fr.redconflict.useful;

import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Messagerie privée : envoi des MP, mémoire du dernier interlocuteur (pour /r)
 * et diffusion aux membres du staff en mode spy.
 */
public class PrivateMessageService {

    private final Map<UUID, UUID> lastInterlocutor = new HashMap<>();
    private final Set<UUID> spies = new HashSet<>();

    public void send(Player from, Player to, String message) {
        from.sendMessage(Text.fmt(RC.MSG_OUT_FMT, to.getName(), message));
        to.sendMessage(Text.fmt(RC.MSG_IN_FMT, from.getName(), message));

        lastInterlocutor.put(from.getUniqueId(), to.getUniqueId());
        lastInterlocutor.put(to.getUniqueId(), from.getUniqueId());

        String spyMessage = Text.fmt(RC.MSG_SPY_FMT, from.getName(), to.getName(), message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.equals(from) && !staff.equals(to)
                    && spies.contains(staff.getUniqueId()) && staff.hasPermission("staff.msgspy")) {
                staff.sendMessage(spyMessage);
            }
        }
    }

    /** @return le dernier interlocuteur du joueur, ou {@code null}. */
    public UUID lastInterlocutorOf(UUID player) {
        return lastInterlocutor.get(player);
    }

    /** Bascule le mode spy du joueur. @return true si le mode est désormais actif. */
    public boolean toggleSpy(UUID player) {
        if (spies.remove(player)) {
            return false;
        }
        spies.add(player);
        return true;
    }
}
