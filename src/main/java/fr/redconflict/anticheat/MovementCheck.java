package fr.redconflict.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contrôles de déplacement : vitesse, cadence de paquets, vol, chute annulée.
 *
 * <p>Tout se joue sur le serveur, à partir des positions qu'il reçoit. Le client
 * ne peut donc pas les désactiver : il peut seulement rester dans les clous, ce
 * qui revient à ne pas tricher.
 *
 * <p><b>Le contrôle le plus décisif ici est {@link Check#TIMER}, pas
 * {@link Check#SPEED}.</b> Un « speedhack » de 1.8 est presque toujours un
 * timer : le client accélère sa propre horloge et envoie 40 ou 60 positions par
 * seconde au lieu de 20. Chaque paquet pris isolément décrit un déplacement
 * parfaitement légal — c'est leur nombre qui trahit. Compter les déplacements
 * par seconde attrape donc ce que mesurer la distance laisse passer, et sans
 * dépendre du terrain, des potions ou de la glace.
 *
 * <p>Les seuils par défaut sont volontairement larges. Un faux positif qui
 * expulse un joueur honnête coûte plus cher qu'un tricheur détecté une minute
 * plus tard, et l'action par défaut n'est de toute façon qu'une alerte.
 */
public class MovementCheck implements Listener {

    /** Fenêtre d'observation de la cadence et de la vitesse. */
    private static final long WINDOW_MS = 1000L;

    /** Après une téléportation ou une poussée, les mesures n'ont pas de sens. */
    private static final long GRACE_MS = 1500L;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    public MovementCheck(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !enabled()) {
            return;
        }
        Player player = event.getPlayer();
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        long now = System.currentTimeMillis();

        // Rotation pure : aucun déplacement à mesurer, mais le paquet compte
        // pour la cadence — un timer accélère aussi les paquets de rotation.
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        synchronized (state) {
            state.packets++;
            state.distance += horizontal;

            if (now - state.windowStart < WINDOW_MS) {
                checkNoFall(player, state, now);
                checkFly(player, to, state, now);
                return;
            }

            double elapsed = (now - state.windowStart) / 1000.0;
            int packets = state.packets;
            double distance = state.distance;
            state.windowStart = now;
            state.packets = 0;
            state.distance = 0;

            if (now - state.graceUntil < 0 || elapsed <= 0) {
                return;
            }
            checkTimer(player, packets, elapsed);
            checkSpeed(player, distance, elapsed);
        }
    }

    /**
     * Cadence de paquets de position. Un client vanilla en envoie une vingtaine
     * par seconde ; au-delà du plafond, son horloge tourne trop vite.
     */
    private void checkTimer(Player player, int packets, double elapsed) {
        if (!enabled("timer")) {
            return;
        }
        double rate = packets / elapsed;
        double max = plugin.getConfig().getDouble("anticheat.timer.max-packets-per-second", 32.0);
        if (rate > max) {
            violations.flag(player, Check.TIMER, String.format("%.0f paquets/s (max %.0f)", rate, max));
        }
    }

    /**
     * Vitesse horizontale soutenue.
     *
     * <p>Le plafond est ajusté à la potion de vitesse, qui est le seul modificateur
     * courant et légitime capable de doubler la valeur. Le reste — glace, pente,
     * sprint-saut — tient dans la marge du plafond par défaut.
     */
    private void checkSpeed(Player player, double distance, double elapsed) {
        if (!enabled("speed") || player.isInsideVehicle() || player.isFlying()) {
            return;
        }
        double speed = distance / elapsed;
        double max = plugin.getConfig().getDouble("anticheat.speed.max-blocks-per-second", 11.0);
        max *= speedPotionFactor(player);
        if (player.getLocation().getBlock().isLiquid()) {
            max *= 1.2; // les courants poussent
        }
        if (speed > max) {
            violations.flag(player, Check.SPEED,
                    String.format("%.1f b/s (max %.1f)", speed, max));
        }
    }

    /** +20 % de plafond par niveau de potion de vitesse, avec de la marge. */
    private double speedPotionFactor(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.SPEED)) {
                return 1.0 + 0.35 * (effect.getAmplifier() + 1);
            }
        }
        return 1.0;
    }

    /**
     * Le client annonce {@code onGround} alors que rien ne le porte.
     *
     * <p>C'est le mensonge qui annule les dégâts de chute : le serveur, croyant
     * le joueur au sol, remet sa distance de chute à zéro. On compare donc ce
     * qu'il annonce à ce qu'il y a réellement sous ses pieds.
     */
    private void checkNoFall(Player player, State state, long now) {
        if (!enabled("nofall") || now - state.graceUntil < 0 || player.isInsideVehicle()) {
            return;
        }
        if (!player.isOnGround() || player.getFallDistance() > 0) {
            return;
        }
        Location at = player.getLocation();
        if (at.getBlock().isLiquid() || isClimbable(at)) {
            return;
        }
        // Deux blocs de marge sous les pieds : les escaliers, dalles et bordures
        // laissent le joueur légèrement au-dessus du bloc plein le plus proche.
        for (double depth = 0.0; depth <= 1.6; depth += 0.4) {
            Location probe = at.clone().subtract(0, depth, 0);
            if (probe.getBlock().getType().isSolid()) {
                return;
            }
        }
        violations.flag(player, Check.NOFALL, "au sol annoncé à " + String.format("%.1f", at.getY()));
    }

    /**
     * Maintien ou gain d'altitude prolongé sans support.
     *
     * <p>Volontairement peu sensible : les projectiles, les bulles d'eau, les
     * bordures de bloc et le knockback produisent tous des suspensions courtes.
     * Seule une suspension qui dure est retenue.
     */
    private void checkFly(Player player, Location to, State state, long now) {
        if (!enabled("fly") || now - state.graceUntil < 0
                || player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) {
            state.airborneSince = 0;
            return;
        }
        if (player.isOnGround() || to.getBlock().isLiquid() || isClimbable(to)) {
            state.airborneSince = 0;
            state.airborneY = to.getY();
            return;
        }
        if (state.airborneSince == 0) {
            state.airborneSince = now;
            state.airborneY = to.getY();
            return;
        }
        long airborne = now - state.airborneSince;
        long max = plugin.getConfig().getLong("anticheat.fly.max-airborne-ms", 4000L);
        // Une vraie chute perd de l'altitude : si le joueur descend, il tombe.
        if (to.getY() < state.airborneY - 0.5) {
            state.airborneSince = now;
            state.airborneY = to.getY();
            return;
        }
        if (airborne > max) {
            state.airborneSince = now;
            violations.flag(player, Check.FLY, (airborne / 1000) + " s en l'air sans descendre");
        }
    }

    private static boolean isClimbable(Location location) {
        Material type = location.getBlock().getType();
        return type == Material.LADDER || type == Material.VINE || type == Material.WEB;
    }

    // ── Périodes de grâce ──────────────────────────────────────────────────────

    /** Une téléportation casse toute continuité de position : on ne mesure plus. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        grace(event.getPlayer());
    }

    /** Knockback, explosion, piston : la vitesse imposée n'est pas celle du joueur. */
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void grace(Player player) {
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        synchronized (state) {
            state.graceUntil = System.currentTimeMillis() + GRACE_MS;
            state.airborneSince = 0;
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.enabled", true);
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("anticheat." + key + ".enabled", true);
    }

    private static final class State {
        private long windowStart = System.currentTimeMillis();
        private int packets;
        private double distance;
        private long graceUntil;
        private long airborneSince;
        private double airborneY;
    }
}
