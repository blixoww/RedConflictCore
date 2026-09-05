package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.config.EssentialsConfig;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Met en forme le chat public : préfixe de grade, pseudo, séparateur, message.
 *
 * <p><b>Pourquoi ce listener existe.</b> Sur le serveur Faction, c'est le
 * {@code ChatListener} de RedFaction qui compose chaque ligne à partir de son
 * {@code chat.global_format} — d'où le préfixe visible là-bas. Mais RedFaction
 * n'est pas déployé sur les autres serveurs (Minage, Hub) : sans lui, plus
 * personne n'appelle {@code setFormat()} et Bukkit retombe sur son format
 * vanilla {@code <Pseudo> message}, sans grade. Le Core étant le seul plugin
 * présent partout, c'est ici que le format par défaut a sa place.
 *
 * <p><b>Priorité HIGH, et pas HIGHEST.</b> RedFaction écoute en HIGHEST puis
 * <em>annule</em> l'événement pour réémettre lui-même la ligne. En passant
 * avant lui, ce listener n'entre jamais en concurrence : là où RedFaction est
 * présent, le format posé ici est écrasé par l'annulation ; là où il est absent,
 * il s'applique. Aucun doublon, aucune détection de plugin tiers à maintenir.
 *
 * <p><b>{@code {prefix}} plutôt que {@code %luckperms_prefix%}.</b> Le
 * placeholder PAPI n'existe que si LuckPerms a pu enregistrer son expansion, ce
 * qui dépend de l'ordre d'activation des plugins — un format qui en dépend
 * s'affiche silencieusement sans grade le jour où l'ordre change. Le hook Vault
 * Chat de LuckPerms, lui, est enregistré dès l'activation ; c'est la source
 * fiable. PlaceholderAPI reste appliqué au reste du format pour qui veut y
 * glisser d'autres placeholders.
 */
public class ChatFormatListener implements Listener {

    /** Un placeholder PlaceholderAPI non résolu, à effacer plutôt qu'à afficher. */
    private static final Pattern UNRESOLVED = Pattern.compile("%[a-zA-Z0-9_]+%");

    private final EssentialsConfig config;

    public ChatFormatListener(EssentialsConfig config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!config.chatFormatEnabled()) {
            return;
        }
        String format = config.chatFormat();
        if (format == null || format.trim().isEmpty()) {
            return;
        }
        event.setFormat(build(event.getPlayer(), format));
    }

    /**
     * Compose le format Bukkit final.
     *
     * <p>L'ordre des étapes n'est pas interchangeable. Les placeholders sont
     * résolus tant que la chaîne ne contient encore aucun code de
     * {@code String.format} ; puis <b>tout {@code %} est doublé</b>, parce que
     * Bukkit passe ce format à {@code String.format()} et qu'un préfixe de grade
     * contenant un pourcentage ferait sinon lever une exception en plein chat ;
     * seulement ensuite {@code {player}} et {@code {message}} deviennent les
     * arguments {@code %1$s} et {@code %2$s}.
     */
    private String build(Player player, String format) {
        String out = expandPlaceholders(player, format);
        out = UNRESOLVED.matcher(out).replaceAll("");
        out = out.replace("{prefix}", vaultPrefix(player));
        out = out.replace("%", "%%");
        out = out.replace("{player}", "%1$s").replace("{message}", "%2$s");
        return ChatColor.translateAlternateColorCodes('&', out);
    }

    /**
     * Préfixe du joueur vu par Vault Chat, ou chaîne vide s'il n'y en a pas.
     *
     * <p>Appelé depuis un événement asynchrone : LuckPerms sert les données des
     * joueurs connectés depuis son cache mémoire, sans toucher la base.
     */
    private String vaultPrefix(Player player) {
        try {
            RegisteredServiceProvider<Chat> rsp =
                    Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp == null) {
                return "";
            }
            String prefix = rsp.getProvider().getPlayerPrefix(player);
            return prefix != null ? prefix : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** PlaceholderAPI est optionnel : accès par réflexion, comme dans RankResolver. */
    private String expandPlaceholders(Player player, String format) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method setPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
            Object out = setPlaceholders.invoke(null, player, format);
            return out instanceof String ? (String) out : format;
        } catch (Exception e) {
            return format;
        }
    }
}
