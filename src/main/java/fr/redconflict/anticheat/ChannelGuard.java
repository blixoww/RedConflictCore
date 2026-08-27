package fr.redconflict.anticheat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filtre d'entrée commun à tous les canaux de messages du client moddé.
 *
 * <p>Un canal de plugin est du code serveur exposé au réseau. Avant ce garde,
 * chaque poignée lisait les octets reçus dans un {@code try} dont le
 * {@code catch} était vide : un client pouvait envoyer des milliers de paquets
 * malformés par seconde, chacun levant une exception avalée en silence, sans
 * qu'aucune trace n'apparaisse ni qu'aucun compteur ne monte. Le seul effet
 * visible aurait été le serveur qui rame.
 *
 * <p>Trois limites, appliquées avant toute lecture :
 * <ul>
 *   <li><b>Taille</b> — un message de gameplay tient dans quelques centaines
 *       d'octets. Au-delà du plafond, on jette sans lire.</li>
 *   <li><b>Débit</b> — un compteur glissant par joueur, tous canaux confondus.
 *       Au-delà, les messages sont jetés jusqu'à la seconde suivante.</li>
 *   <li><b>Trace</b> — les rejets alimentent le {@link ViolationTracker}, donc
 *       les alertes staff. Un client qui inonde se signale tout seul.</li>
 * </ul>
 *
 * <p>Ce garde ne juge pas le contenu : c'est le rôle de chaque poignée. Il
 * garantit seulement qu'aucune poignée n'est appelée trop souvent ni avec un
 * tampon déraisonnable.
 */
public class ChannelGuard {

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<UUID, Window>();

    public ChannelGuard(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    /**
     * @return {@code true} si le message peut être traité, {@code false} s'il
     *         doit être ignoré sans être lu.
     */
    public boolean accept(Player player, String channel, byte[] message) {
        if (!enabled()) {
            return true;
        }
        if (message == null || message.length == 0 || message.length > maxBytes()) {
            violations.flag(player, Check.CHANNEL_ABUSE,
                    "message " + (message == null ? "nul" : message.length + " o") + " sur " + channel);
            return false;
        }

        Window window = windows.computeIfAbsent(player.getUniqueId(), id -> new Window());
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (now - window.since >= 1000L) {
                window.since = now;
                window.count = 0;
            }
            window.count++;
            if (window.count > maxPerSecond()) {
                // Une seule alerte par seconde saturée : sinon l'inondation de
                // paquets devient une inondation d'alertes, ce qui ne vaut pas mieux.
                if (window.count == maxPerSecond() + 1) {
                    violations.flag(player, Check.CHANNEL_ABUSE,
                            window.count + " messages/s sur " + channel);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Enveloppe une poignée de canal pour lui appliquer le garde sans la modifier.
     *
     * <p>Sept canaux entrants existent, chacun enregistré par son propre module.
     * Les faire tous appeler le garde à la main aurait voulu dire sept
     * constructeurs à changer et sept occasions d'en oublier un — or c'est
     * précisément le canal oublié qui sert de porte d'entrée. Le décorateur
     * déplace la décision au point d'enregistrement, où elle est visible.
     */
    public org.bukkit.plugin.messaging.PluginMessageListener wrap(
            final org.bukkit.plugin.messaging.PluginMessageListener delegate) {
        return (channel, player, message) -> {
            if (accept(player, channel, message)) {
                delegate.onPluginMessageReceived(channel, player, message);
            }
        };
    }

    /** Le compteur de violations, pour les poignées qui veulent signaler. */
    public ViolationTracker violations() {
        return violations;
    }

    /** À la déconnexion : ne pas garder de compteur pour un joueur parti. */
    public void forget(UUID player) {
        windows.remove(player);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.channels.enabled", true);
    }

    private int maxBytes() {
        return plugin.getConfig().getInt("anticheat.channels.max-bytes", 32768);
    }

    private int maxPerSecond() {
        return plugin.getConfig().getInt("anticheat.channels.max-per-second", 60);
    }

    /** Compteur glissant d'une seconde. */
    private static final class Window {
        private long since = System.currentTimeMillis();
        private int count;
    }
}
