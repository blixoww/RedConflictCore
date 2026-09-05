package fr.redconflict.combat;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boost à l'arc : se propulser avec sa propre flèche.
 *
 * <h2>Le geste</h2>
 *
 * <p>On tire une flèche en cloche, on court dessous, elle retombe sur soi et
 * l'on part dans la direction où elle volait. C'est un classique du PvP faction
 * en 1.8, et il est censé marcher en vanilla — la flèche ignore son tireur
 * pendant cinq ticks seulement, et {@code EntityArrow} pousse la victime selon
 * le niveau de Punch.
 *
 * <h2>Pourquoi il ne « sort » jamais pareil deux fois</h2>
 *
 * <p>Deux poussées se superposent sur un tir sur soi, et la seconde gâche la
 * première :
 *
 * <ul>
 *   <li>Punch pousse selon la direction HORIZONTALE de la flèche, à
 *       {@code 0,6} par niveau — c'est le boost recherché, et il vaut zéro sans
 *       l'enchantement ;</li>
 *   <li>le recul des dégâts pousse selon {@code victime - attaquant}. Or ici les
 *       deux sont le même joueur : l'écart est nul, et le moteur tire alors une
 *       direction AU HASARD (la boucle {@code Math.random()} de
 *       {@code EntityLiving.damageEntity}). Ce vecteur aléatoire de 0,4
 *       s'ajoute au boost et le dévie, différemment à chaque tir.</li>
 * </ul>
 *
 * <p>D'où la sensation de « punch cassé » : la puissance dépend du hasard, et un
 * arc sans Punch ne propulse que dans une direction imprévisible.
 *
 * <h2>Ce qu'on fait</h2>
 *
 * <p>Quand un joueur est touché par SA PROPRE flèche, on ne corrige pas les deux
 * poussées : on <b>remplace</b> la vélocité par une seule, orientée selon la
 * flèche et dosée par {@code combat.bow-boost}. Le hasard disparaît, le geste
 * redevient une question de visée, et la puissance se règle sans toucher au
 * moteur.
 *
 * <p>Comme pour le recul au corps à corps, on écrit dans le
 * {@link PlayerVelocityEvent} : c'est le vecteur qui part au client, donc le
 * seul endroit où le changer sans provoquer d'élastique. Une flèche d'un AUTRE
 * joueur n'est pas touchée — c'est du PvP normal, pas un boost.
 */
public class BowBoostListener implements Listener {

    /** Le paquet de vélocité part dans le tick du tir : au-delà, ce n'est plus lui. */
    private static final long WINDOW_MS = 100L;

    /** En dessous, la flèche tombe à la verticale : sa direction ne veut plus rien dire. */
    private static final double MIN_HORIZONTAL = 0.05D;

    private final Plugin plugin;

    /** Dernier tir sur soi, par joueur. */
    private final Map<UUID, Boost> boosts = new ConcurrentHashMap<UUID, Boost>();

    public BowBoostListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Touché par sa propre flèche : on prépare la poussée. Rien n'est modifié
     * ici — le moteur n'a pas encore calculé son recul.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSelfArrow(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Arrow)) {
            return;
        }
        Player player = (Player) event.getEntity();
        Arrow arrow = (Arrow) event.getDamager();
        if (!(arrow.getShooter() instanceof Player)
                || !((Player) arrow.getShooter()).getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        boosts.put(player.getUniqueId(), new Boost(System.currentTimeMillis(),
                direction(arrow, player), power(arrow)));
    }

    /**
     * La vélocité part au client : on la remplace par la poussée voulue.
     *
     * <p>{@code HIGHEST} et non {@code MONITOR} : il faut encore pouvoir écrire,
     * et laisser le dernier mot à un plugin qui annulerait la poussée.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Boost boost = boosts.remove(event.getPlayer().getUniqueId());
        if (boost == null || System.currentTimeMillis() - boost.at > WINDOW_MS) {
            return;
        }
        // Remplacement, pas addition : c'est ce qui retire le vecteur aléatoire
        // du recul de dégâts, dont la direction n'a aucun sens sur un tir sur soi.
        double y = Math.max(event.getVelocity().getY(), vertical());
        event.setVelocity(new Vector(boost.direction.getX() * boost.power, y,
                boost.direction.getZ() * boost.power));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boosts.remove(event.getPlayer().getUniqueId());
    }

    // ── Calcul ─────────────────────────────────────────────────────────────────

    /**
     * Direction horizontale de la poussée : celle de la flèche, normalisée.
     *
     * <p>C'est elle qui rend le geste maîtrisable — on part où l'on a tiré. Une
     * flèche qui retombe à la verticale (tir droit vers le haut) n'a pas de
     * direction utilisable : on prend alors le regard du joueur, jamais le
     * hasard.
     */
    private static Vector direction(Arrow arrow, Player player) {
        Vector velocity = arrow.getVelocity();
        double x = velocity.getX();
        double z = velocity.getZ();
        double length = Math.sqrt(x * x + z * z);
        if (length >= MIN_HORIZONTAL) {
            return new Vector(x / length, 0.0D, z / length);
        }
        double yaw = Math.toRadians(player.getLocation().getYaw());
        return new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    }

    /** Puissance horizontale : socle du tir, plus le niveau de Punch de la flèche. */
    private double power(Arrow arrow) {
        int punch = Math.max(0, arrow.getKnockbackStrength());
        double value = horizontal() + perPunch() * punch;
        return Math.min(value, max());
    }

    // ── Configuration (relue à chaque tir : /red reload suffit) ────────────────

    private boolean enabled() {
        return plugin.getConfig().getBoolean("combat.bow-boost.enabled", true);
    }

    private double horizontal() {
        return plugin.getConfig().getDouble("combat.bow-boost.horizontal", 0.35D);
    }

    private double perPunch() {
        return plugin.getConfig().getDouble("combat.bow-boost.per-punch", 0.60D);
    }

    private double vertical() {
        return plugin.getConfig().getDouble("combat.bow-boost.vertical", 0.40D);
    }

    private double max() {
        return plugin.getConfig().getDouble("combat.bow-boost.max", 2.0D);
    }

    /** Un tir sur soi en attente de sa vélocité. */
    private static final class Boost {
        final long at;
        final Vector direction;
        final double power;

        Boost(long at, Vector direction, double power) {
            this.at = at;
            this.direction = direction;
            this.power = power;
        }
    }
}
