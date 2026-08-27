package fr.redconflict.rtp;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.cooldown.CooldownManager;
import fr.redconflict.cooldown.CooldownType;
import fr.redconflict.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Téléportation aléatoire : préavis de {@code rtp.warmup-seconds}, puis arrivée
 * dans un rayon {@code rtp.min}..{@code rtp.max} du spawn.
 *
 * <p><b>Annulation.</b> Comme /spawn, seul un changement de bloc annule le
 * préavis : tourner la vue (yaw/pitch) ne compte pas. Le bloc de départ est
 * mémorisé au lancement et comparé à chaque déplacement.
 *
 * <p><b>Préparation du terrain.</b> Le préavis ne sert pas qu'à immobiliser le
 * joueur, il sert à préparer l'arrivée. Une colonne est tirée au sort, son chunk
 * est généré et chargé, et la colonne n'est retenue que si elle offre un vrai sol
 * à ciel ouvert avec deux blocs d'air au-dessus — jamais une grotte, une nappe
 * d'eau ou de lave, ni le sommet d'un arbre. Les huit chunks voisins sont ensuite
 * chargés à raison d'un par tick, pour que le serveur ait tout en mémoire au
 * moment de l'envoi au client au lieu de le générer pendant que le joueur tombe.
 * Si la préparation dure plus longtemps que le préavis, la téléportation attend :
 * arriver en retard vaut mieux qu'arriver dans le sol.
 *
 * <p><b>Filet de sécurité.</b> Après l'arrivée, la position est revérifiée
 * pendant deux secondes ; si le joueur se retrouve malgré tout encastré ou passé
 * sous le sol, il est remis sur le point validé.
 *
 * <p>Cooldown de 4 h entre deux /rtp, sauf pour les OP et sur le serveur Minage
 * ({@code database.server-id} = "minage") où le déplacement est libre.
 */
public class RtpService {

    /** Blocs sur lesquels on refuse de déposer un joueur, même quand ils sont pleins. */
    private static final Set<Material> UNSAFE_GROUND = EnumSet.of(
            Material.LAVA, Material.STATIONARY_LAVA,
            Material.WATER, Material.STATIONARY_WATER,
            Material.FIRE, Material.CACTUS, Material.WEB,
            Material.LEAVES, Material.LEAVES_2);

    /** Vérification de la position après l'arrivée : 8 passes toutes les 5 ticks = 2 s. */
    private static final int    SETTLE_PASSES    = 8;
    private static final long   SETTLE_PERIOD    = 5L;
    /** Au-delà, le joueur est reparti de lui-même : on cesse de le surveiller. */
    private static final double SETTLE_RADIUS_SQ = 64.0;

    private final JavaPlugin plugin;
    private final RtpLocationPool pool;
    private final Map<UUID, Preparation> pending = new HashMap<UUID, Preparation>();

    public RtpService(JavaPlugin plugin, RtpLocationPool pool) {
        this.plugin = plugin;
        this.pool = pool;
    }

    /**
     * Le serveur Minage laisse /rtp sans cooldown (déplacement libre dans la mine).
     */
    private boolean isFreeServer() {
        Database db = RedConflictCore.getInstance().getCoreDatabase();
        return db != null && "minage".equalsIgnoreCase(db.getServerId());
    }

    /** Point d'entrée de /rtp : vérifie le cooldown puis lance préavis et préparation. */
    public void request(Player player) {
        boolean bypass = player.isOp();
        if (!bypass && !isFreeServer()) {
            long left = CooldownManager.instance().timeLeft(player, CooldownType.RTP);
            if (left > 0) {
                player.sendMessage(Text.fmt(RC.RTP_COOLDOWN, Text.duration(left)));
                return;
            }
        }

        cancelSilently(player.getUniqueId());

        long warmup = bypass ? 0L : 20L * Math.max(0, plugin.getConfig().getInt("rtp.warmup-seconds", 3));
        Preparation preparation = new Preparation(player, warmup);
        preparation.runTaskTimer(plugin, 1L, 1L);
        pending.put(player.getUniqueId(), preparation);
        player.sendMessage(RC.RTP_TELEPORTING);
    }

    /**
     * Le joueur a bougé : annule le préavis s'il a quitté son bloc de départ.
     * Une rotation de la vue laisse le bloc inchangé et ne déclenche donc rien.
     *
     * @return true si un préavis a été annulé.
     */
    public boolean handleMove(Player player, Location to) {
        Preparation preparation = pending.get(player.getUniqueId());
        if (preparation == null || !preparation.movedFrom(to)) {
            return false;
        }
        cancelSilently(player.getUniqueId());
        player.sendMessage(RC.RTP_CANCELLED);
        return true;
    }

    /** Annule le préavis en cours du joueur, sans message. @return true s'il en existait un. */
    public boolean cancelSilently(UUID player) {
        Preparation preparation = pending.remove(player);
        if (preparation == null) {
            return false;
        }
        preparation.cancel();
        return true;
    }

    /** Bloc que le joueur peut traverser sans dégât (air et décor non plein). */
    static boolean isPassable(Material material) {
        return material == Material.AIR
                || (!material.isSolid() && !UNSAFE_GROUND.contains(material));
    }

    /** Bloc sur lequel on peut déposer un joueur. */
    static boolean isStandable(Material material) {
        return material.isSolid() && !UNSAFE_GROUND.contains(material);
    }

    /**
     * Le point est-il toujours praticable ? Un point préparé il y a une minute a
     * pu être bâti dessus, miné ou noyé entre-temps.
     */
    boolean stillSafe(Location target) {
        World world = target.getWorld();
        if (world == null) {
            return false;
        }
        int x = target.getBlockX();
        int y = target.getBlockY();
        int z = target.getBlockZ();
        return isStandable(world.getBlockAt(x, y - 1, z).getType())
                && isPassable(world.getBlockAt(x, y, z).getType())
                && isPassable(world.getBlockAt(x, y + 1, z).getType());
    }

    // ── Préavis ────────────────────────────────────────────────────────────────

    /**
     * Préavis d'immobilité, puis arrivée sur un point pris dans la réserve.
     *
     * <p>Aucune génération de terrain ici : elle a lieu en arrière-plan dans
     * {@link RtpLocationPool}. C'est ce qui a fait disparaître les blocages du
     * serveur — et les expulsions « Too many packets » qu'ils provoquaient chez
     * le joueur qui venait de taper la commande.
     */
    private final class Preparation extends BukkitRunnable {

        private final Player player;
        private final Location start;
        private final long warmupTicks;
        private final long deadlineTicks;

        private long ticks;

        private Preparation(Player player, long warmupTicks) {
            this.player = player;
            this.start = player.getLocation();
            this.warmupTicks = warmupTicks;
            this.deadlineTicks = warmupTicks
                    + 20L * Math.max(1, plugin.getConfig().getInt("rtp.max-wait-seconds", 10));
        }

        /** Retire cette préparation du registre et arrête la tâche. */
        private void finish() {
            if (pending.get(player.getUniqueId()) == this) {
                pending.remove(player.getUniqueId());
            }
            cancel();
        }

        /** true si {@code to} n'est plus sur le bloc où le préavis a commencé. */
        private boolean movedFrom(Location to) {
            return to.getBlockX() != start.getBlockX()
                    || to.getBlockY() != start.getBlockY()
                    || to.getBlockZ() != start.getBlockZ()
                    || to.getWorld() != start.getWorld();
        }

        @Override
        public void run() {
            ticks++;
            if (!player.isOnline()) {
                finish();
                return;
            }
            if (ticks < warmupTicks) {
                return;
            }
            Location destination = pool.poll(RtpService.this);
            if (destination != null) {
                arrive(destination);
                return;
            }
            // Réserve vide : elle se remplit en fond, on laisse un peu de temps.
            if (ticks >= deadlineTicks) {
                finish();
                player.sendMessage(RC.RTP_NO_SPOT);
            }
        }

        private void arrive(Location destination) {
            finish();

            Location target = destination.clone();
            // La vue du joueur est conservée : /rtp change d'endroit, pas d'orientation.
            target.setYaw(player.getLocation().getYaw());
            target.setPitch(player.getLocation().getPitch());

            player.teleport(target);
            player.setFallDistance(0f);
            player.sendMessage(RC.RTP_SUCCESS);
            if (!isFreeServer()) {
                CooldownManager.instance().set(player, CooldownType.RTP, 4, TimeUnit.HOURS);
            }
            settle(player, target);
        }
    }
    private void settle(final Player player, final Location target) {
        new BukkitRunnable() {
            private int passes;

            @Override
            public void run() {
                if (!player.isOnline() || ++passes > SETTLE_PASSES) {
                    cancel();
                    return;
                }
                Location at = player.getLocation();
                if (at.getWorld() != target.getWorld() || at.distanceSquared(target) > SETTLE_RADIUS_SQ) {
                    cancel(); // le joueur est reparti de lui-même
                    return;
                }
                boolean encased = !isPassable(at.getBlock().getType())
                        || !isPassable(at.getBlock().getRelative(BlockFace.UP).getType());
                boolean sunk = at.getY() < target.getY() - 2;
                if (encased || sunk) {
                    player.teleport(target);
                }
                player.setFallDistance(0f);
            }
        }.runTaskTimer(plugin, SETTLE_PERIOD, SETTLE_PERIOD);
    }
}
