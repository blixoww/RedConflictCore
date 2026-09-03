package fr.redconflict.anticheat;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contrôles de minage : cadence, distance, et détection statistique du X-ray.
 *
 * <p><b>Sur le X-ray, il faut être précis sur ce que ce fichier fait et ne fait
 * pas.</b> Il ne CACHE rien : les minerais partent toujours dans les paquets de
 * chunk, et un client qui les affiche à travers la pierre continuera de les
 * voir. Empêcher cela demande de réécrire les chunks avant envoi, ce qu'un
 * plugin Bukkit ne peut pas faire sans interception de paquets (voir la note de
 * déploiement : PaperSpigot 1.8.8 le fait nativement, c'est la vraie réponse).
 *
 * <p>Ce qu'il fait, c'est mesurer le RÉSULTAT : la proportion de minerais rares
 * dans ce qu'un joueur casse. Un mineur honnête casse des centaines de blocs de
 * pierre pour un diamant ; un joueur qui voit à travers creuse presque
 * directement vers le minerai. La statistique ne ment pas longtemps, et elle a
 * l'avantage d'être insensible à la façon dont le X-ray est implémenté côté
 * client — texture pack, mod, shader ou client entier.
 *
 * <p>C'est une détection, pas une preuve : elle sert à ouvrir une enquête, pas à
 * bannir. L'action par défaut est donc l'alerte, et l'échantillon minimal est
 * volontairement grand pour qu'un coup de chance ne déclenche rien.
 */
public class MiningCheck implements Listener {

    /** Minerais dont la rareté rend la proportion significative. */
    private static final Set<Material> TRACKED_ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.EMERALD_ORE, Material.GOLD_ORE,
            Material.LAPIS_ORE, Material.REDSTONE_ORE, Material.GLOWING_REDSTONE_ORE);

    /** Minerais les plus révélateurs : c'est pour eux qu'on triche. */
    private static final Set<Material> RARE_ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.EMERALD_ORE);

    private static final long WINDOW_MS = 1000L;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final BreakBurst bursts;
    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    public MiningCheck(Plugin plugin, ViolationTracker violations, BreakBurst bursts) {
        this.plugin = plugin;
        this.violations = violations;
        this.bursts = bursts;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("redconflict.anticheat.bypass")
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());

        // Un outil de zone (marteau 3×3) produit neuf casses pour un seul coup.
        // La cadence et la distance jugent le GESTE : elles ne comptent que le
        // premier bloc de la volée. La statistique X-ray, elle, juge le
        // RÉSULTAT et doit compter les neuf — c'est justement parce que les huit
        // blocs voisins lui étaient invisibles qu'elle voyait des mineurs
        // honnêtes ne casser que du minerai.
        boolean continuation = bursts.isContinuation(event);
        if (!continuation) {
            checkDistance(player, block);
            checkRate(player, state);
        }
        checkXray(player, block, state);
    }

    /**
     * Distance au bloc cassé. L'allonge de minage en 1.8 est de 5 blocs (6 en
     * créatif) ; au-delà du plafond, ce n'est pas de la latence.
     */
    private void checkDistance(Player player, Block block) {
        if (!enabled("nuker")) {
            return;
        }
        if (player.getWorld() != block.getWorld()) {
            return;
        }
        double distance = player.getEyeLocation().distance(block.getLocation().add(0.5, 0.5, 0.5));
        double max = plugin.getConfig().getDouble("anticheat.nuker.max-distance", 6.5);
        if (distance > max) {
            violations.flag(player, Check.NUKER,
                    String.format("bloc cassé à %.1f blocs (max %.1f)", distance, max));
        }
    }

    /**
     * Cadence de blocs cassés.
     *
     * <p>Le plafond doit rester haut : une pioche en diamant Efficacité V sur de
     * la pierre casse déjà plusieurs blocs par seconde, et un joueur en créatif
     * ou sur des blocs instantanés (torches, herbe, gravier qui tombe) en casse
     * bien davantage sans tricher. Ce contrôle ne vise que le nuker franc, qui
     * en détruit des dizaines par seconde.
     */
    private void checkRate(Player player, State state) {
        if (!enabled("nuker")) {
            return;
        }
        long now = System.currentTimeMillis();
        int broken;
        synchronized (state) {
            if (now - state.rateSince >= WINDOW_MS) {
                state.rateSince = now;
                state.rateCount = 0;
            }
            broken = ++state.rateCount;
        }
        int max = plugin.getConfig().getInt("anticheat.nuker.max-blocks-per-second", 20);
        if (broken > max) {
            violations.flag(player, Check.NUKER, broken + " blocs/s (max " + max + ")");
        }
    }

    /**
     * Proportion de minerais rares sur un échantillon glissant.
     *
     * <p>On ne compte que ce qui est cassé sous le niveau de la mer : les
     * carrières de surface, le défrichage et les constructions fausseraient tout.
     * L'échantillon est remis à zéro après chaque évaluation pour que le joueur
     * reparte d'une page blanche plutôt que de traîner un ratio à vie.
     */
    private void checkXray(Player player, Block block, State state) {
        if (!enabled("xray")) {
            return;
        }
        // Mondes où la statistique n'a aucun sens : une mine régénérée a une
        // densité de minerais choisie par l'administrateur, pas par la
        // génération vanilla — tout le monde y dépasserait le seuil.
        if (plugin.getConfig().getStringList("anticheat.xray.disabled-worlds")
                .contains(block.getWorld().getName())) {
            return;
        }
        if (block.getY() > plugin.getConfig().getInt("anticheat.xray.max-y", 60)) {
            return;
        }
        Material type = block.getType();
        int sample = plugin.getConfig().getInt("anticheat.xray.sample-size", 750);
        double maxRatio = plugin.getConfig().getDouble("anticheat.xray.max-rare-ratio", 0.045);

        int total;
        int rare;
        synchronized (state) {
            state.mined++;
            if (RARE_ORES.contains(type)) {
                state.rare++;
            }
            if (TRACKED_ORES.contains(type)) {
                state.ores++;
            }
            if (state.mined < sample) {
                return;
            }
            total = state.mined;
            rare = state.rare;
            state.mined = 0;
            state.rare = 0;
            state.ores = 0;
        }

        double ratio = rare / (double) total;
        if (ratio > maxRatio) {
            violations.flag(player, Check.XRAY, String.format(
                    "%d minerais rares sur %d blocs (%.2f %%, seuil %.2f %%)",
                    rare, total, ratio * 100, maxRatio * 100));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("anticheat." + key + ".enabled", true);
    }

    private static final class State {
        private long rateSince = System.currentTimeMillis();
        private int rateCount;
        private int mined;
        private int rare;
        private int ores;
    }
}
