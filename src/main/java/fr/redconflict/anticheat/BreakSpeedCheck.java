package fr.redconflict.anticheat;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast break : le bloc tombe plus vite que l'outil tenu ne le permet.
 *
 * <p><b>Le trou que ça ferme.</b> {@link MiningCheck} compte les blocs par
 * seconde. C'est le bon contrôle pour le nuker, qui en détruit des dizaines,
 * mais il ne dit rien du joueur qui casse UN bloc trop vite : de l'obsidienne en
 * une seconde, un coffre à mains nues instantanément, de la pierre sans pioche.
 * Douze blocs par seconde restent sous n'importe quel plafond de cadence, et
 * pourtant aucun d'eux n'aurait dû tomber.
 *
 * <p><b>Ce qu'on mesure.</b> Le serveur reçoit deux paquets pour chaque bloc :
 * « je commence à creuser » et « j'ai fini ». Entre les deux, il sait tout ce qui
 * fixe la durée — le bloc, l'objet tenu, ses enchantements, les effets actifs —
 * parce que c'est LUI qui tient l'inventaire et les potions. Il recalcule donc la
 * durée que le jeu impose et la compare au temps réellement écoulé. Le client n'a
 * aucune prise là-dessus : mentir sur la durée demanderait d'attendre, ce qui est
 * exactement ne pas tricher.
 *
 * <p><b>Trois précautions contre les faux positifs</b>, parce qu'un contrôle de
 * minage se déclenche sur des milliers d'événements par minute :
 * <ul>
 *   <li>La durée attendue est calculée DEUX fois — avec l'objet du début et
 *       celui de la fin — et c'est la plus courte qui fait foi. Changer d'outil
 *       en cours de minage ne peut donc jamais accuser.</li>
 *   <li>Les malus du jeu (fatigue de minage, sous l'eau, en l'air) sont ignorés :
 *       ils allongent la durée réelle, donc les omettre ne peut que profiter au
 *       joueur.</li>
 *   <li>Les blocs dont la dureté n'est pas dans la table — blocs custom compris —
 *       ne sont pas jugés du tout, sauf dureté déclarée en configuration.</li>
 * </ul>
 */
public class BreakSpeedCheck implements Listener {

    /** Marge fixe couvrant la gigue réseau et le retard de tick, en ms. */
    private static final long JITTER_MS = 150L;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final BreakBurst bursts;

    /** Dernier début de minage connu, par joueur. */
    private final Map<UUID, Dig> digs = new ConcurrentHashMap<UUID, Dig>();

    public BreakSpeedCheck(Plugin plugin, ViolationTracker violations, BreakBurst bursts) {
        this.plugin = plugin;
        this.violations = violations;
        this.bursts = bursts;
    }

    /**
     * Début de minage. On note l'instant ET la durée attendue avec l'objet tenu
     * à cet instant : c'est la moitié de la comparaison, l'autre étant refaite à
     * la casse.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(BlockDamageEvent event) {
        if (!enabled() || exempt(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (block.getType() == null) {
            return;
        }
        Dig dig = new Dig(key(block), System.currentTimeMillis(),
                expectedMs(player, player.getItemInHand(), block.getType()));
        digs.put(player.getUniqueId(), dig);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!enabled() || exempt(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (block.getType() == null) {
            return; // bloc custom absent de l'enum Material : rien à comparer
        }
        // Les huit blocs voisins d'un coup de marteau n'ont pas été creusés :
        // ils n'ont ni début de minage ni durée propre, et les juger reviendrait
        // à signaler huit fois chaque coup d'un outil parfaitement légitime.
        if (bursts.isContinuation(event)) {
            digs.remove(player.getUniqueId());
            return;
        }
        // Outil que la table ne sait pas mesurer : aucun verdict. Sans ce
        // garde, un objet inconnu vaut la vitesse d'une main nue et CHAQUE bloc
        // cassé remonte au staff — ce qui est arrivé aux outils du serveur, dont
        // les paliers manquaient à la table.
        if (!BlockHardness.recognizes(player.getItemInHand())) {
            digs.remove(player.getUniqueId());
            return;
        }
        long expected = expectedMs(player, player.getItemInHand(), block.getType());
        if (expected < 0) {
            return; // bloc inconnu de la table : aucun verdict
        }

        Dig dig = digs.remove(player.getUniqueId());
        if (dig != null && dig.block == key(block) && dig.expectedMs >= 0) {
            // La plus courte des deux estimations : changer d'outil, gagner un
            // effet ou en perdre un pendant le minage ne doit jamais accuser.
            expected = Math.min(expected, dig.expectedMs);
        }

        long floor = plugin.getConfig().getLong("anticheat.fastbreak.min-expected-ms", 400L);
        if (expected < floor) {
            return; // trop court pour qu'un écart signifie quoi que ce soit
        }

        if (dig == null || dig.block != key(block)) {
            // Aucun « je commence à creuser » pour ce bloc : le client a envoyé
            // la fin sans le début. Un client vanilla ne fait jamais ça — mais
            // un rechargement de plugin ou un joueur connecté en cours de minage
            // le produisent aussi, d'où un contrôle séparé et désactivable.
            if (plugin.getConfig().getBoolean("anticheat.fastbreak.flag-missing-start", true)) {
                violations.flag(player, Check.FASTBREAK, "aucun début de minage pour "
                        + block.getType() + " (attendu " + expected + " ms)");
            }
            return;
        }

        long elapsed = System.currentTimeMillis() - dig.startedAt;
        double tolerance = plugin.getConfig().getDouble("anticheat.fastbreak.tolerance", 0.70);
        long margin = plugin.getConfig().getLong("anticheat.fastbreak.latency-margin-ms", JITTER_MS);
        long allowed = (long) (expected * tolerance) - margin;
        if (elapsed < allowed) {
            violations.flag(player, Check.FASTBREAK, String.format(
                    "%s cassé en %d ms (attendu %d ms avec %s)",
                    block.getType(), elapsed, expected, heldName(player)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        digs.remove(event.getPlayer().getUniqueId());
    }

    public void forget(UUID player) {
        digs.remove(player);
    }

    // ── Le calcul du jeu ───────────────────────────────────────────────────────

    /**
     * Durée minimale, en millisecondes, que la 1.8 impose pour ce bloc avec cet
     * objet — ou {@code -1} si le bloc n'est pas jugeable.
     *
     * <p>« Minimale » est le mot important : chaque approximation penche du côté
     * du joueur. On applique les bonus (Efficacité, Célérité) et on omet les
     * malus (Fatigue, sous l'eau, en l'air), ce qui produit la durée la plus
     * courte que le jeu aurait pu accorder. Descendre sous cette valeur ne
     * s'explique par aucun réglage légitime.
     */
    private long expectedMs(Player player, ItemStack held, Material block) {
        float hardness = hardnessOf(block);
        if (hardness < 0f) {
            return -1L;
        }
        if (hardness == 0f) {
            return 0L; // bloc instantané (torche, fleur, TNT...)
        }

        float speed = BlockHardness.toolSpeed(held, block);
        if (speed > 1.0f && held != null) {
            int efficiency = held.getEnchantmentLevel(Enchantment.DIG_SPEED);
            if (efficiency > 0) {
                speed += efficiency * efficiency + 1;
            }
        }
        speed *= hasteFactor(player);
        speed *= (float) plugin.getConfig().getDouble("anticheat.fastbreak.speed-bonus", 1.0);

        float divisor = BlockHardness.canHarvest(held, block) ? 30f : 100f;
        float perTick = speed / hardness / divisor;
        if (perTick >= 1f) {
            return 0L; // cassé en un tick : le jeu l'autorise
        }
        return (long) Math.ceil(1f / perTick) * 50L;
    }

    /**
     * Dureté du bloc : la table du jeu, complétée par la configuration.
     *
     * <p>Les blocs custom du serveur (ids 198-212) n'existent pas dans la table
     * vanilla. Tant qu'ils ne sont pas déclarés sous
     * {@code anticheat.fastbreak.hardness.<id>}, ils ne sont pas jugés — ce qui
     * est le bon défaut : mieux vaut un angle mort qu'une accusation inventée.
     */
    private float hardnessOf(Material block) {
        double declared = plugin.getConfig().getDouble(
                "anticheat.fastbreak.hardness." + block.getId(), -1.0);
        if (declared >= 0) {
            return (float) declared;
        }
        return BlockHardness.hardness(block);
    }

    /** Multiplicateur de Célérité : +20 % par niveau, comme dans le jeu. */
    private static float hasteFactor(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType() != null && effect.getType().equals(PotionEffectType.FAST_DIGGING)) {
                return 1.0f + 0.2f * (effect.getAmplifier() + 1);
            }
        }
        return 1.0f;
    }

    private static String heldName(Player player) {
        ItemStack held = player.getItemInHand();
        return held == null || held.getType() == Material.AIR ? "mains nues" : held.getType().name();
    }

    /** Identité d'un bloc, pour vérifier que la casse concerne le bloc creusé. */
    private static long key(Block block) {
        return ((long) block.getX() & 0x3FFFFFF) << 38
                | ((long) block.getZ() & 0x3FFFFFF) << 12
                | ((long) block.getY() & 0xFFF);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.enabled", true)
                && plugin.getConfig().getBoolean("anticheat.fastbreak.enabled", true);
    }

    private static boolean exempt(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.hasPermission("redconflict.anticheat.bypass");
    }

    /** Un minage en cours : le bloc visé, quand il a commencé, et sa durée due. */
    private static final class Dig {
        private final long block;
        private final long startedAt;
        private final long expectedMs;

        private Dig(long block, long startedAt, long expectedMs) {
            this.block = block;
            this.startedAt = startedAt;
            this.expectedMs = expectedMs;
        }
    }
}
