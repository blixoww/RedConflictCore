package fr.redconflict.essentials.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.regex.Pattern;

/**
 * Retire les codes de couleur des messages des joueurs sans permission.
 *
 * <p>Deux raisons de le faire, et la seconde compte plus que la première.
 * D'abord la lisibilité : {@code §k} rend le texte illisible, {@code §0} l'écrit
 * en noir sur fond sombre, et un joueur peut ainsi noyer le chat. Ensuite
 * l'usurpation : avec les codes, n'importe qui peut composer une ligne qui
 * ressemble trait pour trait à un message du staff ou à une annonce du serveur.
 *
 * <p><b>Les deux caractères sont retirés, pas seulement {@code &}.</b> Un client
 * modifié peut envoyer directement le caractère de section {@code §}, que la
 * plupart des filtres oublient parce que le client vanilla ne le tape pas. Ne
 * filtrer que {@code &} laisserait donc la porte ouverte à ceux-là mêmes qu'on
 * veut arrêter.
 *
 * <p>Priorité LOWEST : le message est nettoyé avant que quiconque ne le mette en
 * forme ou ne le journalise.
 */
public class ChatColorListener implements Listener {

    /** Permission qui autorise les codes de couleur en chat. */
    public static final String PERMISSION = "redconflict.chat.color";

    /** {@code &} ou {@code §} suivi d'un code de couleur ou de format valide. */
    private static final Pattern CODES = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(PERMISSION)) {
            return;
        }
        String message = event.getMessage();
        String stripped = CODES.matcher(message).replaceAll("");
        if (!stripped.equals(message)) {
            event.setMessage(stripped);
        }
    }
}
