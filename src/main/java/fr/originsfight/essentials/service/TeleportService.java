package fr.originsfight.essentials.service;

import fr.originsfight.essentials.Messages;
import fr.originsfight.essentials.config.EssentialsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Téléportations avec délai d'attente ("warmup") à la Essentials :
 * le joueur doit rester immobile et ne pas prendre de dégâts pendant N secondes,
 * sinon la téléportation est annulée. La position de départ est toujours
 * enregistrée pour /back, et le cooldown de la commande n'est armé que si la
 * téléportation aboutit.
 *
 * <p>La destination est fournie par un {@link Supplier} évalué au moment du départ
 * (utile pour /tpa : on rejoint la position <i>actuelle</i> de la cible, pas celle
 * qu'elle avait au moment de l'acceptation).
 */
public class TeleportService {

    /** Permission qui saute le délai d'attente. */
    public static final String BYPASS_WARMUP = "redconflict.teleport.bypass";

    private final Plugin plugin;
    private final EssentialsConfig config;
    private final CooldownService cooldowns;
    private final BackService backService;

    private final Map<UUID, PendingTeleport> pending = new HashMap<>();

    public TeleportService(Plugin plugin, EssentialsConfig config,
                           CooldownService cooldowns, BackService backService) {
        this.plugin = plugin;
        this.config = config;
        this.cooldowns = cooldowns;
        this.backService = backService;
    }

    /**
     * Téléporte le joueur après le délai d'attente configuré.
     *
     * @param cooldownKey clé de cooldown armée à l'arrivée (null = pas de cooldown)
     */
    public void delayedTeleport(final Player player, final Supplier<Location> destination,
                                final String cooldownKey) {
        int warmup = config.warmupSeconds();
        if (warmup <= 0 || player.hasPermission(BYPASS_WARMUP)) {
            complete(player, destination, cooldownKey);
            return;
        }

        // Une nouvelle demande remplace silencieusement la précédente.
        cancelSilently(player.getUniqueId());

        player.sendMessage(Messages.fmt(Messages.TP_WARMUP, warmup));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                complete(player, destination, cooldownKey);
            }
        }, warmup * 20L);

        pending.put(player.getUniqueId(), new PendingTeleport(task, player.getLocation()));
    }

    /** Téléportation immédiate (commandes admin type /tp), avec enregistrement /back. */
    public void teleportNow(Player player, Location destination) {
        backService.record(player);
        player.teleport(destination);
    }

    private void complete(Player player, Supplier<Location> destination, String cooldownKey) {
        Location location = destination.get();
        if (location == null || location.getWorld() == null) {
            player.sendMessage(Messages.TP_DEST_INVALID);
            return;
        }
        backService.record(player);
        player.teleport(location);
        player.sendMessage(Messages.TP_DONE);
        if (cooldownKey != null) {
            cooldowns.arm(player.getUniqueId(), cooldownKey, config.cooldownSeconds(cooldownKey));
        }
    }

    // ── Annulation (pilotée par TeleportGuardListener) ─────────────────────────

    /** Le joueur a changé de bloc pendant l'attente. */
    public void handleMove(Player player, Location to) {
        if (!config.cancelOnMove()) return;
        PendingTeleport waiting = pending.get(player.getUniqueId());
        if (waiting == null || !waiting.movedFrom(to)) return;
        cancel(player, Messages.TP_CANCELLED_MOVE);
    }

    /** Le joueur a subi des dégâts pendant l'attente. */
    public void handleDamage(Player player) {
        if (!config.cancelOnDamage()) return;
        if (pending.containsKey(player.getUniqueId())) {
            cancel(player, Messages.TP_CANCELLED_DAMAGE);
        }
    }

    /** Nettoyage à la déconnexion. */
    public void clear(UUID player) {
        cancelSilently(player);
    }

    private void cancel(Player player, String message) {
        PendingTeleport waiting = pending.remove(player.getUniqueId());
        if (waiting != null) {
            waiting.task.cancel();
            player.sendMessage(message);
        }
    }

    private void cancelSilently(UUID player) {
        PendingTeleport waiting = pending.remove(player);
        if (waiting != null) {
            waiting.task.cancel();
        }
    }

    /** Téléportation en attente : tâche planifiée + bloc de départ. */
    private static final class PendingTeleport {
        private final BukkitTask task;
        private final Location start;

        private PendingTeleport(BukkitTask task, Location start) {
            this.task = task;
            this.start = start;
        }

        /** true si {@code to} n'est plus sur le même bloc que la position de départ. */
        private boolean movedFrom(Location to) {
            return to.getBlockX() != start.getBlockX()
                    || to.getBlockY() != start.getBlockY()
                    || to.getBlockZ() != start.getBlockZ()
                    || to.getWorld() != start.getWorld();
        }
    }
}
