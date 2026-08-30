package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entité fantôme — le seul contrôle qui produise une preuve et non un indice.
 *
 * <h2>La différence de nature avec tous les autres</h2>
 *
 * <p>Chaque autre contrôle du module mesure une grandeur et la compare à un
 * seuil : une distance, une cadence, un angle. Ce seuil est nécessairement un
 * compromis — assez haut pour épargner le joueur à 250 ms de latence, donc assez
 * haut pour qu'une triche réglée juste en dessous passe. C'est la limite de
 * fond : on discute toujours d'un chiffre, et le tricheur discute avec nous.
 *
 * <p>Celui-ci ne mesure rien. Il place le joueur devant une situation dans
 * laquelle un client honnête <b>ne peut pas</b> agir, et regarde s'il agit. La
 * réponse est binaire. Il n'y a pas de seuil à régler, pas de latence à
 * pardonner, pas de faux positif à craindre : la conclusion ne vient pas d'une
 * statistique mais d'une impossibilité géométrique.
 *
 * <h2>L'impossibilité en question</h2>
 *
 * <p>Pour désigner une cible, le client vanilla lance un rayon depuis l'oeil
 * <b>le long du vecteur de visée</b> et retient la première entité rencontrée
 * dans l'allonge du jeu ({@code EntityRenderer.getMouseOver}). Ce rayon part
 * droit devant. Une entité située <b>derrière</b> le joueur n'est donc jamais
 * candidate : aucune valeur de latence, aucun désynchronisme, aucun angle mort
 * ne peut la faire entrer dans un rayon qui pointe dans l'autre sens.
 *
 * <p>On dépose donc une entité invisible derrière le joueur, quelques centaines
 * de millisecondes, et on vérifie à l'instant du coup que l'angle entre son
 * regard et la direction de l'entité dépasse {@code min-angle-degrees}. Si le
 * paquet d'attaque la nomme quand même, il n'a pas pu être produit par la boucle
 * de visée du jeu : il a été fabriqué. Une aura qui parcourt la liste des
 * entités à portée — c'est-à-dire toutes — mord immédiatement.
 *
 * <p><b>Elle est invisible pour deux raisons distinctes</b>, et les deux
 * comptent. Pour que le joueur honnête ne voie rien d'anormal ; et pour qu'il ne
 * puisse pas la frapper par accident même en se retournant, puisqu'on ne vise
 * pas ce qu'on ne voit pas. La condition d'angle rend cet accident impossible de
 * toute façon — les deux protections se recouvrent, à dessein.
 *
 * <h2>Ce qui protège le joueur honnête</h2>
 *
 * <ol>
 *   <li>Le fantôme n'appartient qu'à un joueur. Un coup porté par quelqu'un
 *       d'autre ne prouve rien et est ignoré.</li>
 *   <li>L'angle est vérifié <b>au moment du coup</b>, pas à la pose. Un joueur
 *       qui se retourne entre-temps ne déclenche rien.</li>
 *   <li>Le fantôme ne rend aucun dégât et n'en reçoit aucun : l'événement est
 *       annulé dans tous les cas, y compris quand il ne prouve rien.</li>
 *   <li>Il ne vit que {@code lifetime-ms} et ne se pose que sur un joueur déjà
 *       engagé au corps à corps — jamais sur un joueur qui traverse la carte.</li>
 * </ol>
 *
 * <h2>Conséquence sur la sanction</h2>
 *
 * <p>C'est le seul contrôle du module dont le seuil par défaut vaut 1. Compter
 * les répétitions n'a de sens que pour absorber le bruit d'une mesure ; ici il
 * n'y a pas de mesure, donc pas de bruit. Un déclenchement suffit, et se défend.
 *
 * <p>L'action reste malgré tout {@code alert} au départ : le raisonnement est
 * solide, mais il n'a pas encore tourné sur ce serveur, avec ses plugins et ses
 * mondes. Regarde-le se déclencher une fois sur quelqu'un dont tu es sûr, puis
 * passe-le en {@code command} pour bannir.
 */
public class HoneypotCheck implements Listener {

    /** Marqueur posé sur l'entité, pour que le reste du serveur la reconnaisse. */
    public static final String META = "rc-honeypot";

    /**
     * Dispersion aléatoire autour du dos, en degrés.
     *
     * <p>Une position toujours exactement opposée serait reconnaissable, et un
     * tricheur averti apprendrait à ignorer ce qui se trouve pile à 180 degrés.
     * La dispersion reste volontairement modeste : elle suffit à brouiller le
     * motif, et plus elle est large plus l'angle mesuré au coup se rapproche du
     * seuil — donc plus le piège rate de vraies auras sans rien gagner.
     */
    private static final double SPREAD = 18.0D;

    /**
     * Hauteur de pose au-dessus des pieds du joueur, en blocs.
     *
     * <p>Posée au sol, la boîte du fantôme se retrouve nettement SOUS l'oeil du
     * joueur : la direction vers elle plonge d'une quinzaine de degrés, et
     * l'angle mesuré chute dès que le joueur regarde vers le bas — il passait
     * alors sous le seuil et la triche échappait au piège. Décalée pour que la
     * boîte enjambe la hauteur des yeux, la géométrie ne dépend plus que du
     * lacet, qui est justement ce qu'on veut mesurer.
     */
    private static final double HEIGHT_OFFSET = 0.6D;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final Random random = new Random();

    /** Fantômes vivants, indexés par l'identifiant de l'entité posée. */
    private final Map<UUID, Phantom> phantoms = new ConcurrentHashMap<UUID, Phantom>();

    /** Dernier corps à corps porté par chaque joueur. */
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<UUID, Long>();

    /** Dernière pose, pour espacer les fantômes d'un même joueur. */
    private final Map<UUID, Long> lastSpawn = new ConcurrentHashMap<UUID, Long>();

    private BukkitTask task;

    /**
     * Passe à vrai si la pose échoue : le contrôle se tait alors définitivement.
     * Un anti-triche qui jette une exception toutes les trente secondes fait plus
     * de mal que la triche qu'il cherche.
     */
    private boolean broken;

    public HoneypotCheck(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    /** Un fantôme posé : pour qui, où, et jusqu'à quand. */
    private static final class Phantom {
        final UUID target;
        final Entity entity;
        final long expiresAt;
        Phantom(UUID target, Entity entity, long expiresAt) {
            this.target = target;
            this.entity = entity;
            this.expiresAt = expiresAt;
        }
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    public void start() {
        if (task != null || !enabled()) {
            return;
        }
        // Un passage toutes les 10 ticks suffit : on décide de poser, on ne
        // mesure rien. La durée de vie du fantôme est portée par son horodatage,
        // pas par la cadence de ce balayage.
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                sweep();
            }
        }, 20L, 10L);
    }

    public void stop() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) { }
            task = null;
        }
        for (Phantom p : phantoms.values()) {
            remove(p);
        }
        phantoms.clear();
        lastCombat.clear();
        lastSpawn.clear();
    }

    public void forget(UUID uuid) {
        lastCombat.remove(uuid);
        lastSpawn.remove(uuid);
        for (Iterator<Map.Entry<UUID, Phantom>> it = phantoms.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Phantom> e = it.next();
            if (e.getValue().target.equals(uuid)) {
                remove(e.getValue());
                it.remove();
            }
        }
    }

    // ── Pose ─────────────────────────────────────────────────────────────────

    private void sweep() {
        long now = System.currentTimeMillis();

        // 1. Retirer les fantômes arrivés à terme.
        for (Iterator<Map.Entry<UUID, Phantom>> it = phantoms.entrySet().iterator(); it.hasNext(); ) {
            Phantom p = it.next().getValue();
            if (now >= p.expiresAt || p.entity == null || !p.entity.isValid()) {
                remove(p);
                it.remove();
            }
        }

        if (broken || !enabled()) {
            return;
        }

        // 2. En poser de nouveaux sur les joueurs engagés.
        long engageWindow = 1000L * Math.max(1, plugin.getConfig()
                .getInt("anticheat.honeypot.engage-window-seconds", 20));
        long interval = 1000L * Math.max(5, plugin.getConfig()
                .getInt("anticheat.honeypot.interval-seconds", 30));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!eligible(player)) {
                continue;
            }
            Long combat = lastCombat.get(player.getUniqueId());
            if (combat == null || now - combat > engageWindow) {
                continue;   // pas au corps à corps : rien à tendre
            }
            Long last = lastSpawn.get(player.getUniqueId());
            if (last != null && now - last < interval) {
                continue;
            }
            if (hasPhantom(player.getUniqueId())) {
                continue;
            }
            lastSpawn.put(player.getUniqueId(), now);
            place(player, now);
        }
    }

    /**
     * Le joueur peut-il recevoir un fantôme ?
     *
     * <p>On écarte le mode créatif et le spectateur : l'allonge y est différente
     * et aucune aura ne s'y joue. On écarte aussi les joueurs exemptés, pour ne
     * pas poser une entité qui ne servira à rien.
     */
    private boolean eligible(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (player.hasPermission("redconflict.anticheat.bypass")) {
            return false;
        }
        return !plugin.getConfig().getStringList("anticheat.honeypot.disabled-worlds")
                .contains(player.getWorld().getName());
    }

    private boolean hasPhantom(UUID target) {
        for (Phantom p : phantoms.values()) {
            if (p.target.equals(target)) return true;
        }
        return false;
    }

    /**
     * Dépose le fantôme dans le dos du joueur : son lacet retourné, brouillé de
     * {@link #SPREAD}, à hauteur d'yeux via {@link #HEIGHT_OFFSET}.
     */
    private void place(Player player, long now) {
        try {
            double distance = plugin.getConfig().getDouble("anticheat.honeypot.spawn-distance", 2.6D);
            double yaw = player.getLocation().getYaw() + 180.0D
                    + (random.nextDouble() * 2.0D - 1.0D) * SPREAD;
            double rad = Math.toRadians(yaw);

            Location base = player.getLocation();
            Location at = base.clone();
            at.setX(base.getX() - Math.sin(rad) * distance);
            at.setY(base.getY() + HEIGHT_OFFSET);
            at.setZ(base.getZ() + Math.cos(rad) * distance);
            at.setYaw((float) yaw);
            at.setPitch(0.0F);

            if (!fits(at)) {
                return;     // dans un mur : on retentera au prochain intervalle
            }

            Entity entity = spawn(at);
            if (entity == null || !entity.isValid()) {
                return;
            }
            entity.setMetadata(META, new FixedMetadataValue(plugin, player.getUniqueId().toString()));

            long life = Math.max(250L, plugin.getConfig().getLong("anticheat.honeypot.lifetime-ms", 1500L));
            phantoms.put(entity.getUniqueId(), new Phantom(player.getUniqueId(), entity, now + life));

        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("[AC] Honeypot désactivé — pose impossible : " + t);
        }
    }

    /**
     * De la place pour la boîte : sinon l'entité se retrouve encastrée.
     *
     * <p>Trois blocs, parce qu'avec {@link #HEIGHT_OFFSET} la boîte s'étend de
     * 0,6 à environ 2,6 au-dessus du sol et mord donc sur un troisième niveau.
     */
    private boolean fits(Location at) {
        World world = at.getWorld();
        if (world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return false;
        }
        Block block = world.getBlockAt(at);
        for (int dy = 0; dy <= 2; dy++) {
            if (block.getRelative(0, dy, 0).getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Crée l'entité, invisible et inerte.
     *
     * <p>Le porte-armure est le support par défaut, et de loin le plus sûr : pas
     * de son, pas de combustion au soleil, pas d'intelligence, pas de butin, et
     * une boîte de collision normale. Un mob classique reste possible — certaines
     * auras ne visent que ce qui peut riposter — mais il faut alors le rendre
     * invisible, l'empêcher de brûler et de frapper, ce qu'on fait ici.
     */
    private Entity spawn(Location at) {
        EntityType type = type();
        if (type == EntityType.ARMOR_STAND) {
            ArmorStand stand = (ArmorStand) at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setCustomNameVisible(false);
            stand.setRemoveWhenFarAway(false);
            return stand;
        }

        Entity entity = at.getWorld().spawnEntity(at, type);
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            living.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false));
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 10, false));
            living.setRemoveWhenFarAway(false);
            living.setCanPickupItems(false);
            living.setCustomNameVisible(false);
            living.setFireTicks(0);
        }
        return entity;
    }

    private void remove(Phantom p) {
        if (p == null || p.entity == null) {
            return;
        }
        try {
            if (p.entity.hasMetadata(META)) {
                p.entity.removeMetadata(META, plugin);
            }
            p.entity.remove();
        } catch (Throwable ignored) { }
    }

    // ── Détection ────────────────────────────────────────────────────────────

    /**
     * Le verdict.
     *
     * <p>Priorité {@code LOWEST} et {@code ignoreCancelled = false} : on veut
     * voir le coup même si un autre plugin l'annule ensuite. Ce qui prouve la
     * triche, c'est que le paquet ait été envoyé, pas que les dégâts aient été
     * appliqués — un tricheur en zone protégée reste un tricheur.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        // 1. Le fantôme ne blesse jamais personne.
        if (event.getDamager() != null && event.getDamager().hasMetadata(META)) {
            event.setCancelled(true);
            return;
        }

        Phantom phantom = phantoms.get(event.getEntity().getUniqueId());
        if (phantom == null) {
            // Coup ordinaire : sert seulement à savoir qui est au corps à corps.
            if (event.getDamager() instanceof Player) {
                lastCombat.put(event.getDamager().getUniqueId(), System.currentTimeMillis());
            }
            return;
        }

        // 2. Un fantôme ne subit jamais de dégâts, quoi qu'il arrive ensuite.
        event.setCancelled(true);

        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();

        // 3. Le fantôme n'appartient qu'à un joueur. Un coup venu d'ailleurs ne
        //    prouve rien : l'entité n'a pas été posée dans SON dos.
        if (!attacker.getUniqueId().equals(phantom.target)) {
            phantoms.remove(event.getEntity().getUniqueId());
            remove(phantom);
            return;
        }

        // 4. L'angle, mesuré à l'instant du coup et pas à la pose.
        double angle = minAngleToBox(attacker, phantom.entity);
        double min = plugin.getConfig().getDouble("anticheat.honeypot.min-angle-degrees", 100.0D);

        phantoms.remove(event.getEntity().getUniqueId());
        remove(phantom);

        if (angle < min) {
            return;     // il s'est retourné : on ne conclut rien
        }

        violations.flag(attacker, Check.HONEYPOT, String.format(Locale.ROOT,
                "entité fantôme frappée à %.0f° du regard (impossible en vanilla)", angle));
    }

    /**
     * Le PLUS PETIT angle entre le regard et un point de la boîte de l'entité.
     *
     * <p>On ne vise pas le centre mais le point de la boîte le plus favorable au
     * joueur — même principe que {@link PositionHistory#minDistanceToBox}, pour
     * la même raison. Le rayon de visée du jeu teste la boîte entière, pas son
     * centre : conclure sur le centre reviendrait à accuser quelqu'un pour un
     * angle qu'il n'avait pas. En retenant le minimum, on affirme que
     * <b>aucun</b> point de la cible n'était devant lui, ce qui est la seule
     * formulation qui se défende.
     *
     * <p>La boîte retenue, 0,6 × 2,0, couvre largement le porte-armure comme le
     * zombie. La surestimer ne peut que réduire l'angle mesuré, donc épargner le
     * joueur : l'erreur, s'il y en a une, est toujours dans son sens.
     */
    private double minAngleToBox(Player attacker, Entity entity) {
        Location eye = attacker.getEyeLocation();
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0E-6D) {
            return 0.0D;
        }
        Location at = entity.getLocation();
        double half = 0.3D;
        double height = 2.0D;

        double best = 180.0D;
        for (int sx = -1; sx <= 1; sx++) {
            for (int sz = -1; sz <= 1; sz++) {
                for (int sy = 0; sy <= 2; sy++) {
                    Vector to = new Vector(
                            at.getX() + sx * half - eye.getX(),
                            at.getY() + sy * (height / 2.0D) - eye.getY(),
                            at.getZ() + sz * half - eye.getZ());
                    if (to.lengthSquared() < 1.0E-6D) {
                        return 0.0D;    // l'oeil est DANS la boîte : on ne conclut rien
                    }
                    double angle = Math.toDegrees(look.angle(to));
                    if (angle < best) {
                        best = angle;
                    }
                }
            }
        }
        return best;
    }

    /** Aucun mob ne prend le fantôme pour cible : il n'est là pour personne. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && event.getTarget().hasMetadata(META)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    // ── Configuration ────────────────────────────────────────────────────────

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.honeypot.enabled", true)
                && plugin.getConfig().getBoolean("anticheat.enabled", true);
    }

    /** Type de support, avec repli sur le porte-armure si la valeur est douteuse. */
    private EntityType type() {
        String name = plugin.getConfig().getString("anticheat.honeypot.entity-type", "ARMOR_STAND");
        try {
            EntityType t = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            if (t.isSpawnable() && t.getEntityClass() != null
                    && LivingEntity.class.isAssignableFrom(t.getEntityClass())) {
                return t;
            }
        } catch (Exception ignored) { }
        return EntityType.ARMOR_STAND;
    }

    /** Les fantômes vivants, pour un éventuel diagnostic. */
    public List<Entity> live() {
        List<Entity> out = new ArrayList<Entity>();
        for (Phantom p : phantoms.values()) {
            if (p.entity != null && p.entity.isValid()) out.add(p.entity);
        }
        return out;
    }
}
