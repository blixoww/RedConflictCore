package fr.redconflict.anticheat;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconnaît les casses qui appartiennent au même coup de pioche.
 *
 * <p><b>Pourquoi ce fichier existe.</b> Le marteau 3×3 casse neuf blocs par
 * coup, et chacun passe désormais par un {@code BlockBreakEvent} — c'est ce qui
 * lui fait respecter les protections et compter dans les statistiques. Mais les
 * contrôles qui mesurent une CADENCE comptent alors neuf blocs là où le joueur
 * n'a donné qu'un coup : le plafond « blocs par seconde » du nuker se
 * déclencherait sur un marteau parfaitement légitime, et le contrôle de vitesse
 * de minage signalerait huit casses sans début de minage à chaque coup.
 *
 * <p><b>La distinction, et elle n'est pas cosmétique.</b> Un contrôle de cadence
 * mesure ce que le joueur FAIT : il doit compter des coups. Une statistique de
 * minerais mesure ce qu'il OBTIENT : elle doit compter des blocs, tous les
 * blocs — c'est précisément parce que les huit blocs voisins étaient invisibles
 * que le X-ray statistique voyait des joueurs honnêtes ne casser que du minerai.
 *
 * <h2>Deux bornes, et sans elles la porte serait grande ouverte</h2>
 *
 * <p>Une définition large de « même coup » désarmerait le contrôle qu'elle est
 * censée protéger : il suffirait de casser en grappe serrée pour n'être jamais
 * compté. Les deux bornes ci-dessous ferment ça.
 *
 * <ul>
 *   <li><b>Le même tick, pas la même seconde.</b> Les neuf casses d'un marteau
 *       sont produites par une seule boucle : elles sont séparées de quelques
 *       microsecondes. Le minage humain le plus rapide de la 1.8 — Efficacité V
 *       et Célérité sur de la pierre — laisse un tick entier, 50 ms. Une fenêtre
 *       de 25 ms sépare donc les deux sans ambiguïté.</li>
 *   <li><b>Huit blocs au plus.</b> Un outil de zone en casse huit autour du
 *       sien ; un nuker n'a pas de limite. Passé le huitième, la volée est
 *       close et les suivants comptent : un nuker reste donc compté au moins une
 *       fois par volée, soit largement au-dessus du plafond de cadence.</li>
 * </ul>
 *
 * <p><b>Une seule mémoire pour deux appelants.</b> Le même événement traverse
 * plusieurs contrôles ; la réponse est mémorisée sur l'identité de l'événement
 * pour qu'ils obtiennent tous la même, quel que soit leur ordre. Les événements
 * Bukkit étant traités un par un sur le thread principal, un seul emplacement de
 * mémoire suffit.
 */
public final class BreakBurst {

    /** Durée d'une volée. Bien en dessous d'un tick (50 ms) : voir l'en-tête. */
    private static final long WINDOW_MS = 25L;

    /** Blocs rattachables à un coup, au plus : les huit voisins d'un 3×3. */
    private static final int MAX_BLOCKS = 8;

    /** Rayon autour du bloc visé : 1 pour du 3×3, 2 de marge. */
    private static final int RADIUS = 2;

    private final Map<UUID, Burst> bursts = new ConcurrentHashMap<UUID, Burst>();

    private BlockBreakEvent memoEvent;
    private boolean memoAnswer;

    /**
     * Cette casse prolonge-t-elle le coup précédent, ou en ouvre-t-elle un ?
     *
     * @return {@code true} si le bloc fait partie de la même volée qu'un bloc
     *         cassé le même tick, tout près — donc s'il ne doit pas être compté
     *         une seconde fois par un contrôle de cadence
     */
    public boolean isContinuation(BlockBreakEvent event) {
        if (event == memoEvent) {
            return memoAnswer;
        }
        memoEvent = event;
        memoAnswer = compute(event.getPlayer(), event.getBlock());
        return memoAnswer;
    }

    private boolean compute(Player player, Block block) {
        long now = System.currentTimeMillis();
        Burst burst = bursts.get(player.getUniqueId());

        if (burst != null
                && now - burst.startedAt <= WINDOW_MS
                && burst.blocks < MAX_BLOCKS
                && burst.world == block.getWorld().getUID().hashCode()
                && Math.abs(burst.x - block.getX()) <= RADIUS
                && Math.abs(burst.y - block.getY()) <= RADIUS
                && Math.abs(burst.z - block.getZ()) <= RADIUS) {
            burst.blocks++;
            return true;
        }

        bursts.put(player.getUniqueId(), new Burst(block, now));
        return false;
    }

    public void forget(UUID player) {
        bursts.remove(player);
    }

    /** Une volée en cours : le bloc visé, l'instant du coup, ce qui a suivi. */
    private static final class Burst {
        private final int x, y, z, world;
        private final long startedAt;
        private int blocks;

        private Burst(Block block, long startedAt) {
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
            this.world = block.getWorld().getUID().hashCode();
            this.startedAt = startedAt;
        }
    }
}
