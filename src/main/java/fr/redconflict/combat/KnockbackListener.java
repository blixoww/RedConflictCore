package fr.redconflict.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Réduction du recul au corps à corps.
 *
 * <h2>Où l'on intervient, et pourquoi là</h2>
 *
 * <p>Le recul est calculé par le serveur au moment du coup, puis envoyé au
 * client dans un paquet de vélocité. Juste avant cet envoi, CraftBukkit émet un
 * {@link PlayerVelocityEvent} dont la valeur est modifiable — c'est le seul
 * endroit où l'on peut changer le recul sans toucher au moteur et sans que le
 * client voie deux vélocités différentes. Corriger la position après coup, à
 * l'inverse, produirait un joueur qui part puis revient : l'élastique bien connu.
 *
 * <h2>Ne réduire QUE le recul d'un coup</h2>
 *
 * <p>Cet événement se déclenche pour toute poussée : plaque de lancement,
 * explosion, canne à pêche, piston. Les diviser toutes casserait des mécaniques
 * qui n'ont rien à voir. On ne touche donc qu'une vélocité qui suit, dans le
 * même tick, un coup au corps à corps porté à ce joueur.
 *
 * <h2>Séparer l'enchantement du reste — mais PAS le sprint</h2>
 *
 * <p>Un multiplicateur unique réduirait tout dans la même proportion :
 * Knockback II resterait quatre fois plus violent qu'un coup nu, simplement
 * quatre fois plus violent de moins haut. Or c'est précisément l'enchantement
 * qui envoie les joueurs à l'autre bout de la carte.
 *
 * <p>On reconstitue donc les termes du calcul vanilla — {@code 0,4} de base dans
 * {@code EntityLiving.a(...)}, plus {@code 0,5} par niveau de poussée dans
 * {@code EntityHuman.attack} — en séparant ce que le jeu, lui, confond : le
 * sprint y compte pour un niveau de Knockback, alors que le coup en sprint est
 * le coup NORMAL de la 1.8. Il suit donc le facteur de base, pas celui des
 * enchantements (voir {@link #horizontalScale}).
 *
 * <pre>
 *   vanilla = 0,4 + (sprint + n) x 0,5
 *   voulu   = (0,4 + sprint x 0,5) x horizontal + n x 0,5 x enchant
 *   facteur = voulu / vanilla
 * </pre>
 *
 * <p>Avec les valeurs livrées : un coup, courant ou non, pousse à 55 % du
 * vanilla ; un coup en sprint avec Knockback II pousse à 34 % du sien, ce qui
 * reste 30 % de plus qu'un sprint nu. L'enchantement pousse donc toujours
 * davantage, mais il ne décide plus du combat à lui seul — et le sprint, lui,
 * garde exactement le même poids relatif qu'en vanilla.
 */
public class KnockbackListener implements Listener {

    /** Recul de base d'un coup, dans {@code EntityLiving.a(...)}. */
    private static final double BASE_PUSH = 0.4;

    /** Poussée ajoutée par niveau de Knockback (et par le sprint). */
    private static final double LEVEL_PUSH = 0.5;

    /**
     * Fenêtre entre le coup et le paquet de vélocité.
     *
     * <p>Les deux ont lieu dans le même tick — 50 ms. Cent laisse la marge d'un
     * tick lent sans risquer d'attraper une poussée sans rapport, qui aurait de
     * toute façon dû se produire dans le dixième de seconde suivant un coup.
     */
    private static final long WINDOW_MS = 100L;

    private final Plugin plugin;

    /** Dernier coup reçu par joueur, avec le facteur qu'il mérite. */
    private final Map<UUID, Hit> hits = new ConcurrentHashMap<UUID, Hit>();

    /** L'avertissement de serveur non corrigé n'est utile qu'une fois. */
    private volatile boolean staleVectorWarned;

    public KnockbackListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Un coup au corps à corps : on note l'instant et le facteur, sans rien
     * modifier. Le calcul du recul n'a pas encore eu lieu.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player)) {
            return;
        }
        // Corps à corps seulement. Les flèches, les explosions et les dégâts de
        // zone poussent par d'autres chemins, avec d'autres équilibrages.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Entity damager = event.getDamager();
        // La vélocité d'AVANT le recul : le coup n'est pas encore appliqué quand
        // cet événement part. Elle sert de témoin — voir onVelocity.
        hits.put(victim.getUniqueId(), new Hit(System.currentTimeMillis(),
                horizontalScale(knockbackLevel(damager), isSprinting(damager)),
                victim.getVelocity()));
    }

    /**
     * La vélocité part au client : c'est le moment de la réduire.
     *
     * <p>{@code HIGHEST} et non {@code MONITOR} : il faut encore pouvoir écrire
     * dans l'événement, et laisser le dernier mot à un éventuel plugin qui
     * annulerait la poussée.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Hit hit = hits.remove(event.getPlayer().getUniqueId());
        if (hit == null || System.currentTimeMillis() - hit.at > WINDOW_MS) {
            return; // poussée sans rapport avec un coup : plaque, explosion, piston
        }
        Vector velocity = event.getVelocity();

        // Garde-fou : sur un serveur qui passe la MAUVAISE vélocité à cet
        // événement, la réduire revient à supprimer le recul (voir warnStaleVector).
        // On le reconnaît à ceci : le vecteur reçu est exactement celui d'avant le
        // coup. Un vrai recul ajoute toujours 0,4 quelque part, il ne peut pas
        // être identique.
        if (hit.before != null && same(hit.before, velocity)) {
            warnStaleVector();
            return;
        }

        event.setVelocity(new Vector(
                velocity.getX() * hit.horizontal,
                velocity.getY() * vertical(),
                velocity.getZ() * hit.horizontal));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hits.remove(event.getPlayer().getUniqueId());
    }

    // ── Calcul ─────────────────────────────────────────────────────────────────

    /** Niveau de l'enchantement Knockback sur l'arme de l'attaquant. */
    private static int knockbackLevel(Entity damager) {
        if (!(damager instanceof LivingEntity)) {
            return 0;
        }
        EntityEquipment equipment = ((LivingEntity) damager).getEquipment();
        ItemStack weapon = equipment == null ? null : equipment.getItemInHand();
        return weapon == null ? 0 : weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    /** L'attaquant courait-il ? Le jeu n'accorde ce bonus qu'aux joueurs. */
    private static boolean isSprinting(Entity damager) {
        return damager instanceof Player && ((Player) damager).isSprinting();
    }

    /**
     * Facteur à appliquer au recul horizontal de ce coup.
     *
     * <p><b>Le sprint n'est PAS un enchantement, et les confondre supprime le
     * combat.</b> Le jeu ajoute un niveau de poussée quand l'attaquant court, au
     * même titre qu'un niveau de Knockback — mais ce que cela représente n'a rien
     * à voir : le coup en sprint est le coup NORMAL de la 1.8, celui qui fait
     * exister le W-tap et tout le jeu de déplacement. La première version de ce
     * calcul lui appliquait le facteur des enchantements : un coup en sprint ne
     * gardait plus que 26 % de sa poussée, à peine plus qu'un coup à l'arrêt, et
     * le recul semblait avoir disparu.
     *
     * <p>Le sprint suit donc {@code horizontal}, comme le recul de base ; seul
     * l'enchantement suit {@code enchant} :
     *
     * <pre>
     *   vanilla = 0,4 + (sprint + niveau) x 0,5
     *   voulu   = (0,4 + sprint x 0,5) x horizontal + niveau x 0,5 x enchant
     * </pre>
     */
    private double horizontalScale(int level, boolean sprinting) {
        double base = Math.max(0, plugin.getConfig().getDouble("combat.knockback.horizontal", 0.55));
        double enchant = Math.max(0, plugin.getConfig().getDouble("combat.knockback.enchant", 0.15));

        double sprintPush = sprinting ? LEVEL_PUSH : 0.0;
        double vanilla = BASE_PUSH + sprintPush + level * LEVEL_PUSH;
        double wanted = (BASE_PUSH + sprintPush) * base + level * LEVEL_PUSH * enchant;
        return vanilla <= 0 ? 1.0 : wanted / vanilla;
    }

    private double vertical() {
        return Math.max(0, plugin.getConfig().getDouble("combat.knockback.vertical", 0.75));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("combat.knockback.enabled", true);
    }

    private static boolean same(Vector a, Vector b) {
        return Math.abs(a.getX() - b.getX()) < 1.0E-6
                && Math.abs(a.getY() - b.getY()) < 1.0E-6
                && Math.abs(a.getZ() - b.getZ()) < 1.0E-6;
    }

    /**
     * Avertit une fois que le serveur ne peut pas faire fonctionner ce module.
     *
     * <p>{@code EntityHuman.attack} de CraftBukkit 1.8 passe à
     * {@link PlayerVelocityEvent} la vélocité de la victime AVANT le coup, et non
     * le recul qu'il s'apprête à envoyer. Un plugin qui « réduit le recul »
     * multiplie donc la motion précédente, et son {@code setVelocity} écrase le
     * recul que le moteur venait de calculer : le joueur ne part plus du tout.
     *
     * <p>Le fork corrige la ligne ({@code new Vector(entity.motX, motY, motZ)}).
     * Tant qu'il n'est pas reconstruit, ce module s'efface plutôt que de casser
     * le combat — le recul reste vanilla, ce qui est le bon repli.
     */
    private void warnStaleVector() {
        if (staleVectorWarned) {
            return;
        }
        staleVectorWarned = true;
        plugin.getLogger().warning("[Combat] Réduction du recul INACTIVE : ce serveur passe à "
                + "PlayerVelocityEvent la vélocité d'avant le coup au lieu du recul. "
                + "La toucher supprimerait le recul au lieu de le réduire. "
                + "Correctif : EntityHuman.attack, passer new Vector(entity.motX, entity.motY, "
                + "entity.motZ) à l'événement, puis reconstruire le serveur.");
    }

    /** Un coup reçu : quand, de quel facteur, et la motion qui précédait. */
    private static final class Hit {
        private final long at;
        private final double horizontal;
        private final Vector before;

        private Hit(long at, double horizontal, Vector before) {
            this.at = at;
            this.horizontal = horizontal;
            this.before = before;
        }
    }
}
