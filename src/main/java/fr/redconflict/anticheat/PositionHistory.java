package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historique des positions des joueurs, pour rejouer le passé au moment d'un
 * coup.
 *
 * <h2>Le problème que ça résout</h2>
 *
 * <p>Quand un joueur frappe, le serveur reçoit le coup <b>après</b> un aller
 * simple réseau. Entre-temps la cible a bougé. Comparer la position de
 * l'attaquant à la position <i>actuelle</i> de la cible mesure donc une distance
 * que personne n'a jamais vue : ni le client qui a visé, ni le serveur au moment
 * du clic.
 *
 * <p>La parade habituelle est de gonfler le plafond : la 1.8 autorise 3 blocs,
 * on tolère 4,2 « pour le ping ». <b>Ce mou est exactement le budget du
 * tricheur.</b> Il est accordé à tout le monde en permanence, y compris à un
 * joueur à 15 ms de ping qui n'en a aucun besoin — et un killaura réglé sur
 * 4,1 blocs passe sous le radar pour toujours.
 *
 * <h2>Ce qu'on fait à la place</h2>
 *
 * <p>On enregistre la position de chaque joueur à chaque tick. À la réception
 * d'un coup, on cherche la position de la cible <b>la plus favorable à
 * l'attaquant</b> dans la fenêtre que sa latence rend plausible. Si même la
 * position la plus généreuse laisse une distance excessive, aucun ping ne peut
 * l'expliquer : c'est une allonge.
 *
 * <p>La latence est ainsi payée <b>à son coût réel, joueur par joueur</b>, et le
 * plafond peut redescendre près de la valeur vanilla. Le tricheur perd le bloc
 * de mou qu'on lui offrait ; le joueur à 200 ms n'est toujours pas inquiété.
 *
 * <p><b>Cette mesure est hors de portée d'un client modifié</b> : elle n'utilise
 * que des positions que le serveur a lui-même enregistrées, tick après tick.
 */
public final class PositionHistory {

    /** 2 secondes à 20 Hz : au-delà, aucune latence de jeu n'est crédible. */
    private static final int MAX_SAMPLES = 40;

    private final Map<UUID, Deque<Sample>> history = new ConcurrentHashMap<>();
    private BukkitTask task;

    /** Une position datée. */
    private static final class Sample {
        final double x, y, z;
        final long at;
        Sample(double x, double y, double z, long at) {
            this.x = x; this.y = y; this.z = z; this.at = at;
        }
    }

    /**
     * Démarre l'échantillonnage.
     *
     * <p>Un tick d'échantillonnage plutôt qu'un écouteur de déplacement :
     * {@code PlayerMoveEvent} ne se déclenche pas à cadence fixe, et un joueur
     * immobile n'en produit aucun — l'historique aurait alors des trous là où
     * on a justement besoin de savoir où il était.
     */
    public void start(Plugin plugin) {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Location l = p.getLocation();
                    Deque<Sample> q = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>(MAX_SAMPLES + 1));
                    q.addLast(new Sample(l.getX(), l.getY(), l.getZ(), now));
                    while (q.size() > MAX_SAMPLES) q.removeFirst();
                }
            }
        }, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) { }
            task = null;
        }
        history.clear();
    }

    public void forget(UUID uuid) {
        history.remove(uuid);
    }

    /**
     * La plus courte distance œil → boîte de la cible sur la fenêtre de latence.
     *
     * <p>On prend le <b>minimum</b>, c'est-à-dire la version des faits la plus
     * favorable à l'attaquant. C'est ce qui rend le verdict incontestable : si
     * ce minimum dépasse encore le plafond, aucune reconstitution du passé ne
     * peut disculper le coup.
     *
     * @param windowMs profondeur de rembobinage, en millisecondes
     * @return la distance minimale, ou la distance actuelle si l'historique est
     *         vide (cible non joueur, ou joueur qui vient d'arriver)
     */
    public double minDistanceToBox(Location eye, Entity target, long windowMs) {
        double best = distanceToBox(eye, target.getLocation(), target instanceof Player);

        Deque<Sample> q = history.get(target.getUniqueId());
        if (q == null || q.isEmpty()) return best;

        long cutoff = System.currentTimeMillis() - Math.max(0L, windowMs);
        boolean isPlayer = target instanceof Player;

        for (Iterator<Sample> it = q.descendingIterator(); it.hasNext(); ) {
            Sample s = it.next();
            if (s.at < cutoff) break;              // file chronologique : on peut sortir
            double d = distanceToBox(eye, s.x, s.y, s.z, isPlayer);
            if (d < best) best = d;
        }
        return best;
    }

    private static double distanceToBox(Location eye, Location base, boolean isPlayer) {
        return distanceToBox(eye, base.getX(), base.getY(), base.getZ(), isPlayer);
    }

    /**
     * Distance de l'œil à la boîte de collision, celle-ci étant approchée par
     * ses dimensions 1.8 : 0,6 bloc de côté, 1,8 de haut pour un joueur.
     */
    private static double distanceToBox(Location eye, double bx, double by, double bz, boolean isPlayer) {
        double height = isPlayer ? 1.8D : 1.0D;
        double half = 0.3D;

        double dx = Math.max(0.0D, Math.abs(eye.getX() - bx) - half);
        double dz = Math.max(0.0D, Math.abs(eye.getZ() - bz) - half);
        double dy;
        if (eye.getY() < by) {
            dy = by - eye.getY();
        } else if (eye.getY() > by + height) {
            dy = eye.getY() - (by + height);
        } else {
            dy = 0.0D;
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
