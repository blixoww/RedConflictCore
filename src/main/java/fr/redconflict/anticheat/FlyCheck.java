package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vol : on ne mesure plus une durée, on vérifie une chute.
 *
 * <p><b>Ce que faisait l'ancien contrôle, et pourquoi ça ne suffisait pas.</b> Il
 * comptait le temps passé en l'air sans perdre d'altitude, et n'alertait qu'au
 * bout de quatre secondes. Un vol qui descend d'un demi-bloc de temps en temps
 * remettait le compteur à zéro et ne déclenchait jamais rien ; c'est exactement
 * ce que fait un « fly » réglé bas, celui qu'on utilise pour tenir un mur ou
 * suivre un adversaire à trois blocs du sol.
 *
 * <p><b>Ce qu'on vérifie à la place.</b> La chute de la 1.8 est déterministe :
 * à chaque tick, {@code dY = (dY - 0,08) x 0,98}. C'est la seule trajectoire
 * verticale qu'un joueur sans support puisse suivre. On échantillonne donc la
 * position de chacun à chaque tick et on compare le déplacement vertical observé
 * à celui que la gravité impose. Trois signatures en sortent :
 * <ul>
 *   <li><b>Sustentation</b> — l'altitude ne bouge pas. Aucune position vanilla ne
 *       tient une altitude constante sans support, pas même une frame.</li>
 *   <li><b>Chute trop lente</b> — le joueur descend, mais moins vite que la
 *       gravité. C'est la signature du vol « discret », qui reste sous tous les
 *       seuils de vitesse.</li>
 *   <li><b>Montée prolongée</b> — un saut monte cinq ticks, huit avec Détente.
 *       Au-delà, plus rien ne pousse vers le haut.</li>
 * </ul>
 *
 * <p><b>Pourquoi un échantillonnage par tick et non {@code PlayerMoveEvent}.</b>
 * L'événement ne se déclenche que si la position ou la vue change : un joueur
 * parfaitement immobile en l'air n'en produit AUCUN, ce qui est précisément le
 * cas qu'on veut attraper. Le tick, lui, tombe toujours.
 *
 * <p><b>Le piège du réseau, et comment il est désamorcé.</b> Une position figée
 * peut vouloir dire « je vole sur place » ou « mon paquet n'est pas arrivé ».
 * Les deux se ressemblent parfaitement. On ne fait donc avancer les compteurs
 * fins que sur un échantillon FRAIS — un où quelque chose a bougé, ne serait-ce
 * que la vue. Un joueur qui décroche du réseau ne déclenche rien ; un joueur qui
 * vole en regardant autour de lui, si.
 */
public class FlyCheck implements Listener {

    /** Accélération de la 1.8, et frottement de l'air appliqué après. */
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /** Après une poussée ou une téléportation, la trajectoire n'est plus à nous. */
    private static final long GRACE_MS = 1500L;

    /** Ticks de battement après le décollage : l'impulsion du saut n'est pas la gravité. */
    private static final int SETTLE_TICKS = 2;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();
    private BukkitTask task;

    public FlyCheck(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    public void start() {
        if (task != null) {
            return;
        }
        long period = Math.max(1, plugin.getConfig().getInt("anticheat.fly.sample-ticks", 1));
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                sweep();
            }
        }, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        states.clear();
    }

    public void forget(UUID player) {
        states.remove(player);
    }

    // ── Passe ──────────────────────────────────────────────────────────────────

    private void sweep() {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)
                || !plugin.getConfig().getBoolean("anticheat.fly.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                sample(player, now);
            } catch (Throwable ignored) {
                // Un joueur en cours de déconnexion ne doit pas casser la passe.
            }
        }
    }

    private void sample(Player player, long now) {
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        Location at = player.getLocation();

        if (exempt(player)) {
            state.reset(at, now);
            return;
        }
        if (now < state.graceUntil) {
            state.reset(at, now);
            return;
        }

        // Fraîcheur : si rien n'a bougé, aucun paquet n'est peut-être arrivé.
        // On garde l'échantillon pour le compteur de temps en l'air, mais on
        // n'en tire aucune conclusion fine.
        boolean fresh = state.hasPrevious
                && (at.getX() != state.x || at.getY() != state.y || at.getZ() != state.z
                    || at.getYaw() != state.yaw || at.getPitch() != state.pitch);

        double dy = state.hasPrevious ? at.getY() - state.y : 0.0;
        boolean airborne = !supported(player, at);

        state.x = at.getX();
        state.y = at.getY();
        state.z = at.getZ();
        state.yaw = at.getYaw();
        state.pitch = at.getPitch();
        state.hasPrevious = true;

        if (!airborne) {
            state.airTicks = 0;
            state.airborneSince = 0L;
            state.airborneY = at.getY();
            state.hoverTicks = 0;
            state.slowFallTicks = 0;
            state.riseTicks = 0;
            state.lastDy = 0.0;
            return;
        }

        state.airTicks++;
        if (state.airborneSince == 0L) {
            state.airborneSince = now;
            state.airborneY = at.getY();
        }
        // Une vraie chute perd de l'altitude : tant qu'elle descend, on repart.
        if (at.getY() < state.airborneY - 0.5) {
            state.airborneSince = now;
            state.airborneY = at.getY();
        }

        if (state.airTicks > SETTLE_TICKS && fresh) {
            checkHover(player, state, dy);
            checkGravity(player, state, dy);
            checkAscend(player, state, dy);
        }
        checkAirborne(player, state, now);

        state.lastDy = dy;
    }

    /**
     * Altitude constante en l'air.
     *
     * <p>Le contrôle le plus net du fichier : la gravité ne laisse jamais
     * {@code dY} valoir zéro deux ticks de suite. Un seul tick à zéro existe — le
     * sommet d'un saut — d'où le compteur, qui exige une série.
     */
    private void checkHover(Player player, State state, double dy) {
        if (!plugin.getConfig().getBoolean("anticheat.hover.enabled", true)) {
            return;
        }
        double epsilon = plugin.getConfig().getDouble("anticheat.hover.epsilon", 0.005);
        if (Math.abs(dy) > epsilon) {
            state.hoverTicks = 0;
            return;
        }
        int needed = plugin.getConfig().getInt("anticheat.hover.ticks", 10);
        if (++state.hoverTicks < needed) {
            return;
        }
        state.hoverTicks = 0;
        if (nearbySupport(player)) {
            return; // posé sur une barque, un cheval, un porte-armures...
        }
        violations.flag(player, Check.HOVER, String.format(
                "altitude figée à %.2f pendant %d ticks", player.getLocation().getY(), needed));
    }

    /**
     * Chute plus lente que la gravité.
     *
     * <p>Le pas suivant ne peut pas être au-dessus de {@code (dY - 0,08) x 0,98} :
     * rien dans le jeu ne freine une chute libre. Descendre plus vite, en
     * revanche, arrive à tout moment — un tick sauté par le réseau rattrape deux
     * pas d'un coup — et n'est donc jamais retenu contre le joueur.
     */
    private void checkGravity(Player player, State state, double dy) {
        double epsilon = plugin.getConfig().getDouble("anticheat.fly.gravity-epsilon", 0.005);
        double predicted = (state.lastDy - GRAVITY) * DRAG;
        if (dy <= predicted + epsilon) {
            state.slowFallTicks = 0;
            return;
        }
        // Une montée est traitée par checkAscend : ici on ne juge que ce qui
        // descend ou stagne, sinon chaque saut compterait.
        if (dy > 0) {
            state.slowFallTicks = 0;
            return;
        }
        int needed = plugin.getConfig().getInt("anticheat.fly.gravity-ticks", 8);
        if (++state.slowFallTicks < needed) {
            return;
        }
        state.slowFallTicks = 0;
        if (nearbySupport(player)) {
            return;
        }
        violations.flag(player, Check.FLY, String.format(
                "chute freinée : %.3f b/tick au lieu de %.3f, %d ticks d'affilée",
                dy, predicted, needed));
    }

    /**
     * Montée prolongée sans support.
     *
     * <p>Un saut monte cinq ticks. Détente III en ajoute trois. Au-delà, plus rien
     * ne pousse — les bulles, l'eau et les échelles sont déjà exclus plus haut,
     * et toute poussée du serveur (knockback, TNT, plaque de lancement) ouvre une
     * période de grâce.
     */
    private void checkAscend(Player player, State state, double dy) {
        if (dy <= 0.001) {
            state.riseTicks = 0;
            return;
        }
        int needed = plugin.getConfig().getInt("anticheat.fly.ascend-ticks", 12)
                + 2 * jumpBoost(player);
        if (++state.riseTicks < needed) {
            return;
        }
        state.riseTicks = 0;
        if (nearbySupport(player)) {
            return;
        }
        violations.flag(player, Check.FLY, String.format(
                "montée de %d ticks sans support (+%.2f b/tick)", needed, dy));
    }

    /**
     * Le filet de sécurité de l'ancien contrôle : rester en l'air longtemps sans
     * jamais redescendre. Il attrape les vols que les compteurs fins ratent —
     * notamment ceux dont la position est figée, où aucun échantillon n'est frais.
     */
    private void checkAirborne(Player player, State state, long now) {
        long max = plugin.getConfig().getLong("anticheat.fly.max-airborne-ms", 4000L);
        if (state.airborneSince == 0L || now - state.airborneSince <= max) {
            return;
        }
        state.airborneSince = now;
        if (nearbySupport(player)) {
            return;
        }
        violations.flag(player, Check.FLY, (max / 1000) + " s en l'air sans redescendre");
    }

    // ── Support ────────────────────────────────────────────────────────────────

    /**
     * Quelque chose porte-t-il le joueur ?
     *
     * <p>On ne fait AUCUNE confiance au {@code onGround} annoncé par le client :
     * c'est justement le champ que le vol falsifie. On regarde ce qu'il y a sous
     * lui, sur les quatre coins de sa boîte de collision — un joueur peut se tenir
     * debout avec son centre au-dessus du vide, porté par le bloc voisin.
     */
    private static boolean supported(Player player, Location at) {
        if (player.isInsideVehicle()) {
            return true;
        }
        World world = at.getWorld();
        if (world == null) {
            return true;
        }
        // Coordonnées entières calculées à la main plutôt que des Location clonées :
        // cette méthode tourne pour chaque joueur à chaque tick, et un clone par
        // sondage ferait quelques dizaines de milliers d'objets par seconde pour
        // rien.
        final int bx = at.getBlockX();
        final int by = at.getBlockY();
        final int bz = at.getBlockZ();

        Material feet = typeAt(world, bx, by, bz);
        if (isLiquid(feet) || isClimbable(feet) || isStandable(feet)) {
            return true;
        }
        // Flotter au ras de l'eau : les pieds sont dans l'air, l'eau est juste
        // dessous. On ne regarde qu'un bloc plus bas — au-delà, survoler l'océan
        // redeviendrait un angle mort.
        if (isLiquid(typeAt(world, bx, by - 1, bz))) {
            return true;
        }
        // Les quatre coins de la boîte de collision : un joueur peut se tenir
        // debout avec son centre au-dessus du vide, porté par le bloc voisin.
        final double half = 0.31;
        for (double dx = -half; dx <= half; dx += 2 * half) {
            for (double dz = -half; dz <= half; dz += 2 * half) {
                int cx = floor(at.getX() + dx);
                int cz = floor(at.getZ() + dz);
                for (double depth = 0.0; depth <= 1.6; depth += 0.4) {
                    Material type = typeAt(world, cx, floor(at.getY() - depth), cz);
                    if (type == null) {
                        return true; // bloc custom inconnu de l'API : on ne juge pas
                    }
                    if (type.isSolid() || isStandable(type) || isClimbable(type)) {
                        return true;
                    }
                }
            }
        }
        // Un rebond de bloc de slime est calculé par le client : le serveur ne
        // pousse rien, donc aucune période de grâce ne s'ouvre. On regarde plus
        // loin sous les pieds pour ne pas prendre un rebond pour un vol.
        for (int depth = 2; depth <= 5; depth++) {
            if (typeAt(world, bx, by - depth, bz) == Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    /** Type du bloc, ou {@code null} hors du monde (sous 0, au-dessus de 255). */
    private static Material typeAt(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return Material.AIR;
        }
        return world.getBlockAt(x, y, z).getType();
    }

    private static int floor(double value) {
        int rounded = (int) value;
        return value < rounded ? rounded - 1 : rounded;
    }

    /**
     * Une entité sous les pieds ? Barque, wagonnet, cheval, porte-armures, un
     * autre joueur : on peut se tenir sur tout ça, et rien de tout ça n'est un
     * bloc. Appelé seulement au moment de signaler — c'est la vérification la
     * plus chère du fichier, elle ne doit pas tourner à chaque tick.
     */
    private static boolean nearbySupport(Player player) {
        try {
            Location at = player.getLocation();
            for (Entity entity : player.getNearbyEntities(1.3, 2.5, 1.3)) {
                if (entity.getLocation().getY() <= at.getY() + 0.2) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true; // dans le doute, on ne signale pas
        }
        return false;
    }

    private static boolean isLiquid(Material type) {
        return type == Material.WATER || type == Material.STATIONARY_WATER
                || type == Material.LAVA || type == Material.STATIONARY_LAVA;
    }

    private static boolean isClimbable(Material type) {
        return type == Material.LADDER || type == Material.VINE || type == Material.WEB;
    }

    /** Surfaces sur lesquelles on se tient sans qu'elles soient « solides ». */
    private static boolean isStandable(Material type) {
        return type == Material.WATER_LILY || type == Material.CARPET || type == Material.SNOW;
    }

    private static int jumpBoost(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType() != null && effect.getType().equals(PotionEffectType.JUMP)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 0;
    }

    private static boolean exempt(Player player) {
        return player.isFlying() || player.getAllowFlight() || player.isDead()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPermission("redconflict.anticheat.bypass");
    }

    // ── Périodes de grâce ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        grace(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        grace(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            grace((Player) event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        grace(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void grace(Player player) {
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        state.graceUntil = System.currentTimeMillis() + GRACE_MS;
    }

    /** Ce que le contrôle retient d'un joueur d'un tick à l'autre. */
    private static final class State {
        private double x, y, z;
        private float yaw, pitch;
        private boolean hasPrevious;
        private double lastDy;
        private int airTicks;
        private int hoverTicks;
        private int slowFallTicks;
        private int riseTicks;
        private long airborneSince;
        private double airborneY;
        private long graceUntil;

        private void reset(Location at, long now) {
            x = at.getX();
            y = at.getY();
            z = at.getZ();
            yaw = at.getYaw();
            pitch = at.getPitch();
            hasPrevious = true;
            lastDy = 0.0;
            airTicks = 0;
            hoverTicks = 0;
            slowFallTicks = 0;
            riseTicks = 0;
            airborneSince = 0L;
            airborneY = at.getY();
        }
    }
}
