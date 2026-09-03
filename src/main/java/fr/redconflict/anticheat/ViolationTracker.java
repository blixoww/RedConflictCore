package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Compteur de violations par joueur et par contrôle, avec décroissance.
 *
 * <p><b>Pourquoi un compteur et pas une sanction immédiate.</b> Aucun contrôle
 * serveur n'est exact : un pic de latence, un bloc cassé pendant un lag, une
 * poussée de knockback mal reconstruite produisent des faux positifs isolés. Un
 * tricheur, lui, produit le même écart des centaines de fois d'affilée. Ce qui
 * distingue les deux n'est pas l'écart, c'est sa répétition — donc on compte, et
 * on n'agit qu'au seuil.
 *
 * <p>Chaque violation ajoute 1 au compteur du contrôle. Le compteur perd un
 * point toutes les {@code decay-seconds} : un joueur honnête qui déclenche une
 * fausse alerte de temps en temps ne monte jamais, un tricheur monte vite.
 *
 * <p><b>Par défaut rien n'est automatique.</b> {@code action: alert} prévient le
 * staff et écrit dans la console, sans toucher au joueur. C'est délibéré : on
 * n'expulse pas des joueurs sur des seuils qui n'ont pas encore été observés sur
 * ce serveur, avec sa latence et ses habitudes. Regarde les alertes pendant
 * quelques jours, ajuste, et passe à {@code kick} quand tu as confiance.
 */
public class ViolationTracker {

    private static final Logger LOG = Logger.getLogger("AntiCheat");

    private final Plugin plugin;
    private final Map<UUID, Map<Check, Counter>> counters = new ConcurrentHashMap<UUID, Map<Check, Counter>>();

    public ViolationTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Enregistre une violation et applique l'action prévue si le seuil est franchi.
     *
     * @param detail texte court expliquant ce qui a été mesuré (va dans l'alerte)
     */
    public void flag(Player player, Check check, String detail) {
        if (player == null || isExempt(player, check)) {
            return;
        }
        int level = bump(player.getUniqueId(), check);
        int threshold = threshold(check);

        if (plugin.getConfig().getBoolean("anticheat.debug", false)) {
            LOG.info("[AC] " + player.getName() + " " + check.key() + " vl=" + level + " (" + detail + ")");
        }
        if (level < threshold) {
            return;
        }
        reset(player.getUniqueId(), check);
        announce(player, check, level, detail);
        applyAction(player, check);
    }

    /** Remet à zéro tous les compteurs du joueur (déconnexion). */
    public void forget(UUID player) {
        counters.remove(player);
    }

    /** Fait décroître tous les compteurs d'un point. À appeler périodiquement. */
    public void decay() {
        for (Map<Check, Counter> byCheck : counters.values()) {
            for (Counter counter : byCheck.values()) {
                if (counter.value > 0) {
                    counter.value--;
                }
            }
        }
    }

    /** Niveau courant d'un contrôle pour un joueur (pour /ac). */
    public int level(UUID player, Check check) {
        Map<Check, Counter> byCheck = counters.get(player);
        if (byCheck == null) {
            return 0;
        }
        Counter counter = byCheck.get(check);
        return counter == null ? 0 : counter.value;
    }

    // ── Interne ────────────────────────────────────────────────────────────────

    private int bump(UUID player, Check check) {
        Map<Check, Counter> byCheck = counters.computeIfAbsent(
                player, id -> new EnumMap<Check, Counter>(Check.class));
        synchronized (byCheck) {
            Counter counter = byCheck.get(check);
            if (counter == null) {
                counter = new Counter();
                byCheck.put(check, counter);
            }
            return ++counter.value;
        }
    }

    private void reset(UUID player, Check check) {
        Map<Check, Counter> byCheck = counters.get(player);
        if (byCheck == null) {
            return;
        }
        synchronized (byCheck) {
            Counter counter = byCheck.get(check);
            if (counter != null) {
                counter.value = 0;
            }
        }
    }

    /**
     * Le staff en vol, en créatif ou porteur de la permission d'exemption n'est
     * pas contrôlé : sinon /fly et /speed déclenchent tout ce qui existe.
     */
    private boolean isExempt(Player player, Check check) {
        if (player.hasPermission("redconflict.anticheat.bypass")) {
            return true;
        }
        if (check == Check.SPEED || check == Check.FLY || check == Check.NOFALL) {
            return player.isFlying() || player.getAllowFlight()
                    || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || player.getGameMode() == org.bukkit.GameMode.SPECTATOR;
        }
        return false;
    }

    private int threshold(Check check) {
        return Math.max(1, plugin.getConfig().getInt(
                "anticheat." + check.key() + ".threshold", 8));
    }

    /**
     * L'action configurée pour ce contrôle, telle qu'écrite.
     *
     * <p>Rendue SANS passer en minuscules, contrairement à la première version :
     * quand la valeur est une commande, elle contient un motif de bannissement
     * qui s'affichera au joueur, et « AntiCheat » ne doit pas devenir
     * « anticheat » en chemin. La comparaison aux mots-clés se fait donc sans
     * tenir compte de la casse, là où elle a lieu.
     */
    private String action(Check check) {
        String global = plugin.getConfig().getString("anticheat.action", "alert");
        String value = plugin.getConfig().getString("anticheat." + check.key() + ".action", global);
        return value == null ? "alert" : value.trim();
    }

    private void announce(Player player, Check check, int level, String detail) {
        String message = "§8[§cAC§8] §f" + player.getName() + " §7— §c" + check.label()
                + " §8(§7" + detail + "§8)";
        LOG.warning("[AC] " + player.getName() + " : " + check.label() + " — " + detail
                + " (seuil atteint, niveau " + level + ")");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("staff.anticheat") || staff.hasPermission("staff.staff") || staff.isOp()) {
                staff.sendMessage(message);
            }
        }
    }

    /**
     * L'action est exécutée sur le thread principal : {@code flag} peut être
     * appelé depuis un canal de plugin, qui n'y est pas.
     *
     * <p><b>Trois mots-clés, et tout le reste est une commande.</b> La forme
     * d'origine exigeait {@code action: command} PLUS une clé {@code command:}
     * séparée ; écrire directement {@code action: "ban %player% ..."} — ce que
     * tout le monde essaie d'abord — ne correspondait à aucune branche et ne
     * faisait donc <em>rien du tout</em>, en silence. Un anti-triche qu'on croit
     * armé et qui ne l'est pas est pire que pas d'anti-triche : la valeur est
     * désormais prise pour ce qu'elle est.
     */
    private void applyAction(Player player, Check check) {
        final String action = action(check);
        if (action.isEmpty() || "alert".equalsIgnoreCase(action)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if ("kick".equalsIgnoreCase(action)) {
                player.kickPlayer("§cComportement anormal détecté §8(" + check.label() + ")"
                        + "\n§7Si tu penses que c'est une erreur, contacte le staff.");
                return;
            }
            // « command » prend la commande dans sa propre clé ; toute autre
            // valeur EST la commande.
            String command = "command".equalsIgnoreCase(action)
                    ? plugin.getConfig().getString("anticheat." + check.key() + ".command", "")
                    : action;
            command = command.trim().replace("%player%", player.getName());
            if (command.isEmpty()) {
                LOG.warning("[AC] " + check.key() + " : action 'command' sans clé 'command' — "
                        + "aucune sanction appliquée.");
                return;
            }
            if (command.startsWith("/")) {
                command = command.substring(1); // la console n'attend pas la barre
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        });
    }

    private static final class Counter {
        private int value;
    }
}
