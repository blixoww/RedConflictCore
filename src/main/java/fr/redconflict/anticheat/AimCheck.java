package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Cohérence entre le coup porté et la direction du regard.
 *
 * <p><b>Pourquoi ce contrôle et pas un seuil de plus.</b> Les contrôles de
 * {@link CombatCheck} mesurent des quantités : une distance, une cadence. Un
 * killaura moderne ne les dépasse plus — il se contente de rester dessous. Le
 * schéma qui circule tient en quatre lignes : n'attaquer que sur un vrai clic
 * physique, une fois par pression, jamais plus de quatre fois par seconde, sur
 * une cible à portée. Cadence humaine, allonge légale, aucun mur : les trois
 * contrôles de quantité passent tous. Ce qui reste faux, c'est que le joueur
 * <b>ne regardait pas sa cible</b> — le paquet d'attaque désigne une entité que
 * son curseur n'a jamais visée.
 *
 * <p>Un client vanilla ne peut pas produire ce coup. Il n'envoie
 * {@code C02PacketUseEntity} que pour l'entité renvoyée par son propre tracé de
 * rayon, depuis l'œil, dans l'axe du regard, à moins de trois blocs. Attaquer
 * une entité hors de cet axe demande de fabriquer le paquet à la main : ce
 * n'est plus un réglage de cheat, c'est sa signature.
 *
 * <p><b>Ce que ça oblige le tricheur à faire.</b> Pour passer ici, il doit
 * réellement tourner la tête vers sa cible avant de frapper — et cette rotation
 * part au serveur, donc à tous les autres joueurs, qui voient sa tête pivoter
 * seule. Il retombe sur l'aura visible d'il y a dix ans, celle que le staff
 * repère à l'œil en spectateur. C'est le but : on ne cherche pas à rendre la
 * triche impossible, on la ramène à une forme qui se voit.
 *
 * <p><b>Le contrôle est volontairement permissif.</b> Trois précautions se
 * cumulent, toutes dans le sens du joueur honnête :
 * <ul>
 *   <li>on ne juge pas sur l'instant mais sur une <b>fenêtre</b> — l'évaluation
 *       est différée de quelques ticks et retient la MEILLEURE des rotations
 *       envoyées autour du coup, croisée avec les positions successives de la
 *       cible. Le client vise avec une rotation que le serveur ne recevra qu'au
 *       tick suivant ; sans cette fenêtre, tout le monde remonterait ;</li>
 *   <li>la boîte de collision est <b>élargie</b> de {@code tolerance-blocks} ;</li>
 *   <li>en dessous de {@code min-angle-degrees} d'écart, on ne compte rien :
 *       un quasi-manqué est du désynchronisme, pas de la triche. Un coup
 *       fabriqué, lui, se trompe de 40, 90 ou 180 degrés.</li>
 * </ul>
 *
 * <p>Ne s'applique qu'aux cibles <b>joueurs</b> : les boîtes de collision des
 * créatures varient trop (araignée large, enderman haut, slime variable) pour
 * qu'un contrôle d'angle y soit fiable, et ce n'est pas là qu'est le sujet.
 *
 * <p>Cette classe porte aussi les deux contrôles qui partagent son historique :
 * le coup sans animation de bras ({@link Check#NO_SWING}) et l'aura qui touche
 * plusieurs joueurs en quelques ticks ({@link Check#MULTI_AURA}).
 */
public class AimCheck implements Listener {

    /** Historique de positions/rotations conservé par joueur. */
    private static final int MAX_FRAMES = 40;
    /** Au-delà, une trame ne sert plus à rien : on la jette. */
    private static final long FRAME_TTL_MS = 2500L;
    /** Coups de bras mémorisés par joueur. */
    private static final int MAX_SWINGS = 16;

    /** Demi-largeur et hauteur de la boîte d'un joueur en 1.8. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** Après une téléportation, positions et rotations n'ont plus de continuité. */
    private static final long GRACE_MS = 1200L;

    /** Garde-fou : si la tâche d'évaluation ne tourne plus, la file ne doit pas enfler. */
    private static final int MAX_PENDING = 4096;

    private final Plugin plugin;
    private final ViolationTracker violations;

    private final Map<UUID, Track> tracks = new ConcurrentHashMap<UUID, Track>();
    private final ConcurrentLinkedQueue<Sample> pending = new ConcurrentLinkedQueue<Sample>();

    private BukkitTask task;

    public AimCheck(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    /** Démarre l'évaluation différée. Une passe tous les 2 ticks suffit. */
    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }, 2L, 2L);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pending.clear();
        tracks.clear();
    }

    public void forget(UUID player) {
        tracks.remove(player);
    }

    // ── Collecte ───────────────────────────────────────────────────────────────

    /**
     * Chaque paquet de position OU de rotation laisse une trame.
     *
     * <p>Attention : CraftBukkit 1.8 n'émet cet événement que si la position a
     * bougé d'au moins 1/256 de bloc <b>ou</b> si l'angle a varié de plus de dix
     * degrés. Un joueur parfaitement immobile qui ajuste sa visée ne produit
     * donc aucune trame — c'est prévu, {@link #frames} retombe alors sur sa
     * position courante, qui est exactement la bonne puisqu'il n'a pas bougé.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !enabled()) {
            return;
        }
        Player player = event.getPlayer();
        Track track = track(player.getUniqueId());
        synchronized (track) {
            track.push(new Frame(System.currentTimeMillis(), to.getX(), to.getY(), to.getZ(),
                    to.getY() + player.getEyeHeight(), to.getYaw(), to.getPitch()));
        }
    }

    /** Une téléportation casse la continuité : les coups de cette fenêtre ne se jugent plus. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Track track = track(event.getPlayer().getUniqueId());
        synchronized (track) {
            track.graceUntil = System.currentTimeMillis() + GRACE_MS;
            track.frames.clear();
        }
    }

    /** Animation de bras : c'est elle qui manque quand le coup est fabriqué. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Track track = track(event.getPlayer().getUniqueId());
        synchronized (track) {
            track.swings[track.swingIndex] = System.currentTimeMillis();
            track.swingIndex = (track.swingIndex + 1) % MAX_SWINGS;
        }
    }

    /**
     * Le coup est seulement enregistré ici : le juger tout de suite reviendrait
     * à ignorer la rotation que le client a déjà envoyée mais que le serveur
     * n'a pas encore lue.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getDamager() instanceof Player)) {
            return;
        }
        Entity victim = event.getEntity();
        if (!(victim instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        Player target = (Player) victim;
        if (attacker.hasPermission("redconflict.anticheat.bypass")) {
            return;
        }
        if (attacker.isInsideVehicle() || target.isInsideVehicle()) {
            return; // l'œil n'est plus là où on le calcule
        }
        long now = System.currentTimeMillis();

        checkMultiTarget(attacker, target, now);

        if (pending.size() < MAX_PENDING) {
            pending.add(new Sample(attacker.getUniqueId(), target.getUniqueId(), now));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    // ── Aura multi-cibles ──────────────────────────────────────────────────────

    /**
     * Plusieurs JOUEURS distincts touchés en quelques ticks.
     *
     * <p>Une main humaine ne peut pas viser trois joueurs différents en un quart
     * de seconde : il faudrait déplacer le curseur entre chaque clic. Une aura,
     * elle, prend la liste des entités proches et les frappe dans la foulée.
     *
     * <p>Restreint aux joueurs exprès. Sur des créatures, un couloir de ferme à
     * mobs produit exactement la même trace sans qu'il y ait triche.
     */
    private void checkMultiTarget(Player attacker, Player target, long now) {
        if (!enabled("multi-aura")) {
            return;
        }
        long window = plugin.getConfig().getLong("anticheat.multi-aura.window-ms", 250L);
        int max = Math.max(2, plugin.getConfig().getInt("anticheat.multi-aura.max-targets", 3));

        Track track = track(attacker.getUniqueId());
        int distinct;
        synchronized (track) {
            track.victims.addLast(new Victim(target.getUniqueId(), now));
            while (!track.victims.isEmpty() && now - track.victims.peekFirst().at > window) {
                track.victims.removeFirst();
            }
            Set<UUID> ids = new HashSet<UUID>();
            for (Victim victim : track.victims) {
                ids.add(victim.id);
            }
            distinct = ids.size();
        }
        if (distinct >= max) {
            violations.flag(attacker, Check.MULTI_AURA,
                    distinct + " joueurs touchés en " + window + " ms");
        }
    }

    // ── Évaluation différée ────────────────────────────────────────────────────

    /**
     * Traite les coups devenus « mûrs », c'est-à-dire ceux dont toute la fenêtre
     * d'observation est passée. La file est chronologique : dès qu'un coup est
     * trop récent, les suivants le sont aussi.
     */
    private void drain() {
        long now = System.currentTimeMillis();
        long ripe = lookAhead() + 60L;
        while (true) {
            Sample sample = pending.peek();
            if (sample == null || now - sample.at < ripe) {
                return;
            }
            pending.poll();
            try {
                evaluate(sample);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[AC] Évaluation de visée abandonnée : " + e);
            }
        }
    }

    private void evaluate(Sample sample) {
        Player attacker = Bukkit.getPlayer(sample.attacker);
        Player target = Bukkit.getPlayer(sample.target);
        if (attacker == null || target == null || !attacker.isOnline() || !target.isOnline()) {
            return;
        }
        Track attackerTrack = tracks.get(sample.attacker);
        Track targetTrack = tracks.get(sample.target);
        if (attackerTrack == null || targetTrack == null) {
            return;
        }
        // Coup porté pendant une période de grâce : rien de mesurable.
        if (sample.at < attackerTrack.graceUntil || sample.at < targetTrack.graceUntil) {
            return;
        }
        if (attacker.getWorld() != target.getWorld()) {
            return;
        }

        checkSwing(attacker, attackerTrack, sample.at);
        checkAim(attacker, target, attackerTrack, targetTrack, sample.at);
    }

    /**
     * Coup porté sans animation de bras dans la fenêtre.
     *
     * <p>Le client vanilla appelle {@code swingItem()} à chaque clic, donc
     * chaque coup légitime est encadré d'un paquet d'animation. Une aura qui
     * fabrique seulement le paquet d'attaque n'en envoie aucun.
     *
     * <p>La fenêtre déborde des deux côtés du coup : en 1.8, le client envoie
     * l'attaque AVANT l'animation dans le même tick, l'ordre d'arrivée n'est
     * donc pas celui qu'on croit. Signal secondaire — une aura qui se déclenche
     * sur un vrai clic produit, elle, une vraie animation.
     */
    private void checkSwing(Player attacker, Track track, long at) {
        if (!enabled("no-swing")) {
            return;
        }
        long before = plugin.getConfig().getLong("anticheat.no-swing.before-ms", 600L);
        long after = plugin.getConfig().getLong("anticheat.no-swing.after-ms", 300L);
        synchronized (track) {
            for (long swing : track.swings) {
                if (swing != 0 && swing >= at - before && swing <= at + after) {
                    return;
                }
            }
        }
        violations.flag(attacker, Check.NO_SWING, "aucune animation de bras autour du coup");
    }

    /**
     * Le cœur du contrôle : une des rotations envoyées autour du coup
     * pointe-t-elle vers une des positions occupées par la cible ?
     */
    private void checkAim(Player attacker, Player target, Track attackerTrack, Track targetTrack, long at) {
        if (!enabled("aim")) {
            return;
        }
        double tolerance = plugin.getConfig().getDouble("anticheat.aim.tolerance-blocks", 0.35);
        double minAngle = plugin.getConfig().getDouble("anticheat.aim.min-angle-degrees", 8.0);
        double range = plugin.getConfig().getDouble("anticheat.reach.max-blocks", 4.2) + 1.5;

        List<Frame> eyes = frames(attackerTrack, at, attacker);
        List<Frame> boxes = frames(targetTrack, at, target);
        if (eyes.isEmpty() || boxes.isEmpty()) {
            return;
        }

        double bestAngle = 181.0;
        for (Frame eye : eyes) {
            double[] direction = look(eye.yaw, eye.pitch);
            for (Frame box : boxes) {
                double minX = box.x - HALF_WIDTH - tolerance;
                double maxX = box.x + HALF_WIDTH + tolerance;
                double minY = box.y - tolerance;
                double maxY = box.y + HEIGHT + tolerance;
                double minZ = box.z - HALF_WIDTH - tolerance;
                double maxZ = box.z + HALF_WIDTH + tolerance;

                // Œil DANS la boîte élargie : au corps à corps le plus serré,
                // aucune direction n'est absurde. On ne conclut pas.
                if (eye.x >= minX && eye.x <= maxX && eye.eye >= minY && eye.eye <= maxY
                        && eye.z >= minZ && eye.z <= maxZ) {
                    return;
                }
                if (rayHitsBox(eye.x, eye.eye, eye.z, direction,
                        minX, minY, minZ, maxX, maxY, maxZ, range)) {
                    return; // une rotation visait bien la cible : rien à dire
                }
                double angle = angleBetween(eye, direction, box);
                if (angle < bestAngle) {
                    bestAngle = angle;
                }
            }
        }
        if (bestAngle > minAngle && bestAngle <= 180.0) {
            violations.flag(attacker, Check.AIM,
                    String.format("cible à %.0f° du regard", bestAngle));
        }
    }

    // ── Géométrie ──────────────────────────────────────────────────────────────

    /** Vecteur unitaire du regard, convention Minecraft. */
    private static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double xz = Math.cos(p);
        return new double[] { -xz * Math.sin(y), -Math.sin(p), xz * Math.cos(y) };
    }

    /**
     * Intersection rayon / boîte alignée, méthode des tranches.
     *
     * <p>Le rayon part de l'œil et ne compte que vers l'avant, jusqu'à
     * {@code range}. Un coup porté « dans le dos » ne peut donc pas passer : la
     * boîte est bien sur la droite du regard, mais derrière l'origine.
     */
    private static boolean rayHitsBox(double ox, double oy, double oz, double[] dir,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ,
                                      double range) {
        double near = 0.0;
        double far = range;

        double[] origin = { ox, oy, oz };
        double[] min = { minX, minY, minZ };
        double[] max = { maxX, maxY, maxZ };

        for (int axis = 0; axis < 3; axis++) {
            double d = dir[axis];
            if (Math.abs(d) < 1.0E-8) {
                // Rayon parallèle à cette paire de faces : il faut déjà être entre elles.
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double t1 = (min[axis] - origin[axis]) / d;
            double t2 = (max[axis] - origin[axis]) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                near = t1;
            }
            if (t2 < far) {
                far = t2;
            }
            if (near > far) {
                return false;
            }
        }
        return true;
    }

    /** Écart angulaire entre le regard et le centre de la cible, en degrés. */
    private static double angleBetween(Frame eye, double[] dir, Frame box) {
        double dx = box.x - eye.x;
        double dy = (box.y + HEIGHT / 2.0) - eye.eye;
        double dz = box.z - eye.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6) {
            return 0.0;
        }
        double dot = (dir[0] * dx + dir[1] * dy + dir[2] * dz) / length;
        if (dot > 1.0) {
            dot = 1.0;
        } else if (dot < -1.0) {
            dot = -1.0;
        }
        return Math.toDegrees(Math.acos(dot));
    }

    // ── Historique ─────────────────────────────────────────────────────────────

    /**
     * Trames retenues autour du coup. Si le joueur n'a envoyé aucun paquet dans
     * la fenêtre, c'est qu'il n'a ni bougé ni tourné : sa position courante EST
     * celle qu'il avait au moment du coup.
     */
    private List<Frame> frames(Track track, long at, Player live) {
        long back = plugin.getConfig().getLong("anticheat.aim.lookback-ms", 400L);
        long forward = lookAhead();
        List<Frame> kept = new ArrayList<Frame>();
        synchronized (track) {
            for (Frame frame : track.frames) {
                if (frame.t >= at - back && frame.t <= at + forward) {
                    kept.add(frame);
                }
            }
        }
        if (kept.isEmpty()) {
            Location location = live.getLocation();
            kept.add(new Frame(at, location.getX(), location.getY(), location.getZ(),
                    location.getY() + live.getEyeHeight(), location.getYaw(), location.getPitch()));
        }
        return kept;
    }

    private long lookAhead() {
        return plugin.getConfig().getLong("anticheat.aim.lookahead-ms", 200L);
    }

    private Track track(UUID player) {
        Track track = tracks.get(player);
        if (track == null) {
            track = new Track();
            Track raced = tracks.putIfAbsent(player, track);
            if (raced != null) {
                track = raced;
            }
        }
        return track;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.enabled", true);
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("anticheat." + key + ".enabled", true);
    }

    // ── Structures ─────────────────────────────────────────────────────────────

    /** Une position et une rotation, datées. */
    private static final class Frame {
        private final long t;
        private final double x;
        private final double y;
        private final double z;
        /** Ordonnée de l'œil, pas des pieds. */
        private final double eye;
        private final float yaw;
        private final float pitch;

        private Frame(long t, double x, double y, double z, double eye, float yaw, float pitch) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
            this.eye = eye;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class Victim {
        private final UUID id;
        private final long at;

        private Victim(UUID id, long at) {
            this.id = id;
            this.at = at;
        }
    }

    /** Coup en attente d'évaluation. */
    private static final class Sample {
        private final UUID attacker;
        private final UUID target;
        private final long at;

        private Sample(UUID attacker, UUID target, long at) {
            this.attacker = attacker;
            this.target = target;
            this.at = at;
        }
    }

    /** Tout ce qu'on garde d'un joueur, borné dans le temps et en taille. */
    private static final class Track {
        private final Deque<Frame> frames = new ArrayDeque<Frame>(MAX_FRAMES);
        private final Deque<Victim> victims = new ArrayDeque<Victim>();
        private final long[] swings = new long[MAX_SWINGS];
        private int swingIndex;
        private long graceUntil;

        private void push(Frame frame) {
            frames.addLast(frame);
            while (frames.size() > MAX_FRAMES) {
                frames.removeFirst();
            }
            for (Iterator<Frame> it = frames.iterator(); it.hasNext(); ) {
                if (frame.t - it.next().t > FRAME_TTL_MS) {
                    it.remove();
                } else {
                    break; // file chronologique : le premier récent arrête le balayage
                }
            }
        }
    }
}
