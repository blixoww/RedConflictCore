package fr.redconflict.combatlog;

import fr.redconflict.RedConflictCore;
import fr.redconflict.cooldown.CooldownType;
import fr.redconflict.packets.PacketBuilder;
import fr.redconflict.cooldown.CooldownManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Pousse au client modifié l'état du Combat Tag pour piloter le widget CombatLog.
 *
 * <p>Canal Bukkit {@value #CHANNEL}, packetId {@value #PACKET_ID}, payload = un {@code long}
 * (millisecondes de combat restantes). Le widget client s'affiche UNIQUEMENT d'après cette valeur :
 * le combat est donc strictement serveur-autoritaire (PvP uniquement, épée ou flèche).
 *
 * <p>Un tick périodique synchronise le compte à rebours et envoie {@code 0} une fois à la fin du
 * combat pour masquer le widget. Le listener appelle aussi {@link #send(Player)} au moment du tag
 * pour un affichage immédiat.
 */
public class CombatLogSender {

    /** Canal court (compatible Bukkit/Spigot) attendu par le client (PacketChannel.COMBATLOG). */
    public static final String CHANNEL = "OF_COMBAT";
    private static final int  PACKET_ID      = 1;
    private static final long INTERVAL_TICKS = 10L; // 0.5s

    private final RedConflictCore plugin;
    private final Set<UUID> inCombat = new HashSet<>();
    private BukkitTask task;

    public CombatLogSender(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            long rem = CooldownManager.instance().timeLeft(p, CooldownType.COMBAT);
            if (rem > 0) {
                send(p, rem);
            } else if (inCombat.contains(p.getUniqueId())) {
                send(p, 0L); // fin de combat → masque le widget côté client
            }
        }
    }

    /** Envoie immédiatement l'état de combat courant d'un joueur (appelé au moment du tag). */
    public void send(Player player) {
        send(player, CooldownManager.instance().timeLeft(player, CooldownType.COMBAT));
    }

    /** Oublie un joueur (à la déconnexion) pour éviter d'accumuler des UUID. */
    public void forget(UUID uuid) {
        inCombat.remove(uuid);
    }

    private void send(Player player, long remainingMillis) {
        long ms = Math.max(0L, remainingMillis);
        try {
            byte[] payload = PacketBuilder.create(PACKET_ID).writeLong(ms).build();
            player.sendPluginMessage(plugin, CHANNEL, payload);
        } catch (Exception ignored) {
            // Client vanilla ou canal non enregistré côté client : sans effet.
        }
        if (ms > 0) {
            inCombat.add(player.getUniqueId());
        } else {
            inCombat.remove(player.getUniqueId());
        }
    }
}
