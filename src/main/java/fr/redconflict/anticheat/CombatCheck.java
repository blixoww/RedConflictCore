package fr.redconflict.anticheat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

/**
 * Contrôles de combat : allonge et coups à travers les murs.
 *
 * <p>La cadence de clics (autoclick) n'est plus ici : elle se compte sur les
 * paquets, voir {@link ClickCheck}.
 *
 * <p>Des mesures que le serveur fait seul, à partir de positions qu'il connaît
 * déjà. Un killaura peut viser parfaitement, il ne peut pas raccourcir la
 * distance qui le sépare de sa cible ni faire disparaître le bloc entre les deux.
 *
 * <p>L'allonge est de loin la plus fiable des trois : en 1.8 elle vaut 3 blocs,
 * et le seul flou vient de la latence, qui déplace la cible entre le moment où
 * le client vise et celui où le serveur reçoit le coup. Le plafond par défaut
 * laisse une marge confortable pour cela ; au-delà, ce n'est plus du ping.
 */
public class CombatCheck implements Listener {

    private final Plugin plugin;
    private final ViolationTracker violations;

    /** Positions passées des joueurs — voir {@link PositionHistory}. */
    private final PositionHistory positions;

    public CombatCheck(Plugin plugin, ViolationTracker violations, PositionHistory positions) {
        this.plugin = plugin;
        this.violations = violations;
        this.positions = positions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)) {
            return;
        }
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        if (attacker.hasPermission("redconflict.anticheat.bypass")) {
            return;
        }
        Entity target = event.getEntity();

        checkReach(attacker, target);
        checkThroughWall(attacker, target);
    }

    /**
     * Distance entre l'œil de l'attaquant et le point le plus proche de la
     * cible.
     *
     * <p>On mesure jusqu'à la boîte de collision et non jusqu'au centre : viser
     * l'épaule d'un joueur est légitime et vaut déjà un demi-bloc de moins que
     * viser son nombril. Prendre le centre ferait remonter tout le monde.
     */
    private void checkReach(Player attacker, Entity target) {
        if (!enabled("reach")) {
            return;
        }
        Location eye = attacker.getEyeLocation();
        if (eye.getWorld() != target.getWorld()) {
            return;
        }

        // ── Compensation de latence ──────────────────────────────────────────
        //
        // On rembobine la cible sur la profondeur que le ping de l'attaquant
        // rend plausible, et on retient la position la PLUS FAVORABLE à
        // celui-ci. La latence est donc payée à son coût réel, joueur par
        // joueur, au lieu d'être forfaitisée dans un plafond gonflé.
        //
        // C'est ce qui permet de ramener le plafond de 4,2 à 3,25 blocs : le
        // mou de 1,2 bloc qu'on accordait à tout le monde — et dont un killaura
        // se servait pour rester sous le seuil — disparaît, sans faire remonter
        // le joueur à 200 ms.
        int ping = pingOf(attacker);
        long margin = plugin.getConfig().getLong("anticheat.reach.latency-margin-ms", 150L);
        long window = Math.min(2000L, Math.max(0, ping) + Math.max(0L, margin));

        double distance = positions.minDistanceToBox(eye, target, window);
        double max = plugin.getConfig().getDouble("anticheat.reach.max-blocks", 3.25);
        if (distance > max) {
            violations.flag(attacker, Check.REACH,
                    String.format("%.2f blocs (max %.2f, ping %d ms)", distance, max, ping));
        }
    }

    /**
     * Ping de l'attaquant, ou 0 s'il est illisible.
     *
     * <p>Lu par réflexion sur {@code EntityPlayer.ping} : l'API Bukkit 1.8 ne
     * l'expose pas. Les accesseurs sont résolus une fois et mémorisés.
     *
     * <p>Un ping introuvable donne 0 : la fenêtre se réduit alors à la marge,
     * donc le contrôle devient plus STRICT, jamais plus permissif. Une panne de
     * mesure ne doit pas ouvrir une porte.
     */
    private int pingOf(Player attacker) {
        try {
            if (pingLookupFailed) return 0;
            if (craftGetHandle == null) {
                craftGetHandle = attacker.getClass().getMethod("getHandle");
                Object handle = craftGetHandle.invoke(attacker);
                entityPingField = handle.getClass().getField("ping");
            }
            Object handle = craftGetHandle.invoke(attacker);
            return entityPingField.getInt(handle);
        } catch (Throwable t) {
            pingLookupFailed = true;
            return 0;
        }
    }

    private java.lang.reflect.Method craftGetHandle;
    private java.lang.reflect.Field  entityPingField;
    private boolean pingLookupFailed;


    /**
     * Distance de l'œil à la boîte de collision de la cible, approchée par sa
     * hauteur : l'API 1.8 n'expose pas de {@code BoundingBox}.
     */
    private static double distanceToBox(Location eye, Entity target) {
        Location base = target.getLocation();
        double height = target instanceof Player ? 1.8 : 1.0;
        double half = 0.3;

        double dx = Math.max(0, Math.abs(eye.getX() - base.getX()) - half);
        double dz = Math.max(0, Math.abs(eye.getZ() - base.getZ()) - half);
        double dy;
        if (eye.getY() < base.getY()) {
            dy = base.getY() - eye.getY();
        } else if (eye.getY() > base.getY() + height) {
            dy = eye.getY() - (base.getY() + height);
        } else {
            dy = 0;
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Bloc plein entre l'œil de l'attaquant et sa cible.
     *
     * <p>Le tracé s'arrête un bloc avant la cible : un joueur collé à un mur
     * d'angle a légitimement de la pierre sur la ligne droite qui le relie à son
     * adversaire, et compter ce cas ferait remonter les combats de tunnel.
     */
    private void checkThroughWall(Player attacker, Entity target) {
        if (!enabled("through-wall")) {
            return;
        }

        Location eye = attacker.getEyeLocation();
        Location targetEye = target.getLocation().add(0, 1.0, 0);

        if (eye.getWorld() != targetEye.getWorld()) {
            return;
        }

        Vector direction = targetEye.toVector().subtract(eye.toVector());
        double length = direction.length();

        if (length < 1.5 || length > 8.0) {
            return;
        }

        Vector step = direction.normalize().multiply(0.25);
        Location current = eye.clone();

        double maxDistance = length - 1.0;
        double travelled = 0.0;

        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        while (travelled < maxDistance) {
            int blockX = current.getBlockX();
            int blockY = current.getBlockY();
            int blockZ = current.getBlockZ();

            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;

            if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                if (!eye.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    return;
                }

                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
            }

            Block block = eye.getWorld().getBlockAt(blockX, blockY, blockZ);

            if (block.getType().isSolid() && block.getType().isOccluding()) {
                violations.flag(
                        attacker,
                        Check.THROUGH_WALL,
                        "à travers " + block.getType()
                                + " sur " + String.format("%.1f", length) + " blocs"
                );
                return;
            }

            current.add(step);
            travelled += 0.25;
        }
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("anticheat." + key + ".enabled", true);
    }
}
