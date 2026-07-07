package fr.originsfight.rtp;

import fr.originsfight.core.text.RC;
import fr.originsfight.core.text.Text;
import fr.originsfight.cooldown.CooldownManager;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.db.Database;
import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Téléportation aléatoire : préavis de 3 s (annulé si le joueur bouge), puis
 * téléportation dans un rayon {@code rtp.min}..{@code rtp.max} du spawn.
 *
 * <p>Cooldown de 4 h entre deux /rtp, sauf pour les OP et sur le serveur
 * Minage ({@code database.server-id} = "minage") où le déplacement est libre.
 */
public class RtpService {

    private static final long WARMUP_TICKS = 20L * 3;

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public RtpService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Point d'entrée de /rtp : vérifie le cooldown puis lance le préavis. */
    public void request(Player player) {
        if (player.isOp()) {
            teleport(player);
            return;
        }
        long left = CooldownManager.instance().timeLeft(player, CooldownType.RTP);
        if (!isFreeServer() && left > 0) {
            player.sendMessage(Text.fmt(RC.RTP_COOLDOWN, Text.duration(left)));
            return;
        }
        pending.put(player.getUniqueId(),
                Bukkit.getScheduler().runTaskLater(plugin, () -> teleport(player), WARMUP_TICKS));
        player.sendMessage(RC.RTP_TELEPORTING);
    }

    /** Annule le préavis en cours du joueur. @return true si un préavis existait. */
    public boolean cancelPending(Player player) {
        BukkitTask task = pending.remove(player.getUniqueId());
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    private void teleport(Player player) {
        pending.remove(player.getUniqueId());
        Location destination = randomLocation();
        if (destination == null) {
            player.sendMessage(RC.ERR_INTERNAL);
            return;
        }
        destination.getWorld().getChunkAt(destination).load();
        player.teleport(destination);
        player.sendMessage(RC.RTP_SUCCESS);
        if (!isFreeServer()) {
            CooldownManager.instance().set(player, CooldownType.RTP, 4, TimeUnit.HOURS);
        }
    }

    /** Position aléatoire dans le monde principal, à distance min..max de l'origine sur chaque axe. */
    private Location randomLocation() {
        World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            return null;
        }
        int min = plugin.getConfig().getInt("rtp.min");
        int max = Math.max(plugin.getConfig().getInt("rtp.max"), min + 1);
        int x = randomCoordinate(min, max);
        int z = randomCoordinate(min, max);
        return new Location(world, x, world.getHighestBlockYAt(x, z) + 3, z);
    }

    private int randomCoordinate(int min, int max) {
        int value = min + random.nextInt(max - min + 1);
        return random.nextBoolean() ? -value : value;
    }

    /** Le serveur Minage laisse /rtp sans cooldown (déplacement libre dans la mine). */
    private boolean isFreeServer() {
        Database db = OriginsFightCore.getInstance().getCoreDatabase();
        return db != null && "minage".equalsIgnoreCase(db.getServerId());
    }
}
