package fr.redconflict.rtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Réserve de points d'arrivée déjà validés, remplie en arrière-plan.
 *
 * <p><b>Pourquoi ce détour.</b> {@code World.loadChunk(x, z, true)} GÉNÈRE le
 * terrain quand il n'existe pas, sur le thread principal. À 1000-9000 blocs du
 * spawn, sur un monde neuf, chaque appel peut coûter des centaines de
 * millisecondes. La version précédente en enchaînait jusqu'à une vingtaine
 * pendant le préavis, un par tick : le serveur passait plusieurs secondes à
 * générer, les paquets des clients s'accumulaient pendant ce temps, et Spigot
 * en vidait des centaines d'un coup au tick suivant — d'où le
 * {@code Too many packets} qui expulsait le joueur qui venait de taper /rtp.
 *
 * <p>La génération est donc sortie du chemin de la commande. Une tâche de fond
 * prépare des points d'avance, <b>un seul chunk à la fois</b>, et lève le pied
 * dès qu'un chargement s'avère long : sur un monde déjà exploré elle ne coûte
 * presque rien, sur un monde neuf elle prend son temps sans que personne ne
 * l'attende. Le /rtp se contente de piocher.
 *
 * <p>Contrepartie assumée : si la réserve est vide, le joueur patiente le temps
 * qu'elle se remplisse, puis est invité à réessayer. Mieux vaut ça qu'un serveur
 * figé et un joueur expulsé.
 */
public class RtpLocationPool {

    /** Chunk central d'abord : une colonne mauvaise est écartée sans charger les voisins. */
    private static final int[][] CHUNK_OFFSETS = {
            {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
    };

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Deque<Location> ready = new ArrayDeque<Location>();

    private BukkitTask task;

    // État du candidat en cours de préparation.
    private Location column;
    private Location landing;
    private int chunkStep = -1;

    /** Nombre de passes à sauter, après un chargement jugé trop long. */
    private int cooldownPasses;

    public RtpLocationPool(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig().getInt("rtp.pool.refill-interval-ticks", 10));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::refillStep, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ready.clear();
    }

    /** Nombre de points d'arrivée disponibles. */
    public int available() {
        return ready.size();
    }

    /**
     * Prend un point validé, ou {@code null} si la réserve est vide.
     *
     * <p>Le point est revérifié : son chunk a pu être déchargé depuis, et le
     * terrain a pu changer (un joueur y a construit, une explosion est passée).
     * Recharger un chunk DÉJÀ généré est une lecture disque, sans commune mesure
     * avec la génération qu'on cherche à éviter.
     */
    public Location poll(RtpService service) {
        while (!ready.isEmpty()) {
            Location candidate = ready.poll();
            World world = candidate.getWorld();
            if (world == null) {
                continue;
            }
            world.loadChunk(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4, false);
            if (service.stillSafe(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ── Remplissage ────────────────────────────────────────────────────────────

    /** Une passe = au plus UN chargement de chunk. C'est toute la discipline. */
    private void refillStep() {
        if (cooldownPasses > 0) {
            cooldownPasses--;
            return;
        }
        int target = Math.max(1, plugin.getConfig().getInt("rtp.pool.size", 8));
        if (ready.size() >= target) {
            return;
        }
        if (chunkStep < 0) {
            column = randomColumn();
            if (column == null) {
                return;
            }
            chunkStep = 0;
        }

        World world = column.getWorld();
        int[] offset = CHUNK_OFFSETS[chunkStep];
        long start = System.currentTimeMillis();
        world.loadChunk((column.getBlockX() >> 4) + offset[0],
                        (column.getBlockZ() >> 4) + offset[1], true);
        long elapsed = System.currentTimeMillis() - start;

        // Un chargement long = génération de terrain vierge. On ralentit : la
        // réserve se remplira plus lentement, le serveur ne bronchera pas.
        long slow = plugin.getConfig().getLong("rtp.pool.slow-load-ms", 40L);
        if (elapsed > slow) {
            cooldownPasses = Math.max(1, plugin.getConfig().getInt("rtp.pool.slow-load-backoff", 4));
        }

        if (chunkStep == 0) {
            landing = safeLanding(column);
            if (landing == null) {
                chunkStep = -1; // colonne suivante à la prochaine passe
                return;
            }
        }
        chunkStep++;
        if (chunkStep >= CHUNK_OFFSETS.length) {
            ready.add(landing);
            landing = null;
            chunkStep = -1;
        }
    }

    private Location randomColumn() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        World world = Bukkit.getWorlds().get(0);
        int min = plugin.getConfig().getInt("rtp.min");
        int max = Math.max(plugin.getConfig().getInt("rtp.max"), min + 1);
        return new Location(world, randomCoordinate(min, max), 0, randomCoordinate(min, max));
    }

    private int randomCoordinate(int min, int max) {
        int value = min + random.nextInt(max - min + 1);
        return random.nextBoolean() ? -value : value;
    }

    /**
     * Descend depuis le ciel jusqu'au premier bloc plein et renvoie le point où
     * poser un joueur, ou {@code null} si la colonne ne convient pas.
     *
     * <p>Partir du ciel garantit une arrivée en surface : le premier bloc plein
     * rencontré est le toit du monde à cet endroit, jamais le plafond d'une
     * grotte.
     */
    private Location safeLanding(Location col) {
        World world = col.getWorld();
        int x = col.getBlockX();
        int z = col.getBlockZ();

        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 3);
        for (int y = top; y > 1; y--) {
            Material ground = world.getBlockAt(x, y, z).getType();
            if (RtpService.isPassable(ground)) {
                continue; // herbe, fleurs, couche de neige… pas encore le sol
            }
            if (!RtpService.isStandable(ground)) {
                return null;
            }
            if (!RtpService.isPassable(world.getBlockAt(x, y + 1, z).getType())
                    || !RtpService.isPassable(world.getBlockAt(x, y + 2, z).getType())) {
                return null;
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }
}
