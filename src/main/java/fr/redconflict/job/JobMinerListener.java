package fr.redconflict.job;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Donne de l'XP Mineur quand un joueur casse un minerai.
 *
 * <p>Un minerai <b>posé à la main</b> ne rapporte rien : sans ça, Silk Touch
 * permettait de poser-casser le même bloc à l'infini. Le suivi est persistant
 * (voir {@link PlacedOreTracker}), et suit les minerais poussés par un piston —
 * sinon il suffisait de décaler le bloc d'une case pour effacer la marque.
 */
public class JobMinerListener implements Listener {

    private final JobManager manager;
    private final JobConfig  config;
    private final PlacedOreTracker placed;

    public JobMinerListener(JobManager manager, JobConfig config, PlacedOreTracker placed) {
        this.manager = manager;
        this.config  = config;
        this.placed  = placed;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Posé à la main : aucune XP, quel que soit celui qui le casse.
        if (placed.consume(block)) return;

        Player player = event.getPlayer();
        Material mat  = block.getType();
        int      data = block.getData();

        // Clé "MATERIAL" ou "MATERIAL:META"
        String keySimple = mat.name();
        String keyMeta   = mat.name() + ":" + data;

        int level = manager.getLevel(player, JobType.MINER);
        int xp = config.getMinerXp(keyMeta, level);
        if (xp == 0) xp = config.getMinerXp(keySimple, level);
        if (xp > 0) manager.giveXp(player, JobType.MINER, xp);
    }

    /**
     * Marque tout bloc posé qui rapporterait de l'XP Mineur à un palier
     * quelconque — pas seulement au palier actuel du joueur : sinon un minerai
     * posé tôt, puis cassé une fois le palier atteint, repayerait.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        String keySimple = block.getType().name();
        if (config.hasMinerXp(keySimple + ":" + block.getData()) || config.hasMinerXp(keySimple)) {
            placed.mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        moveAll(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        // En 1.8, getDirection() d'un retrait donne déjà le sens du déplacement
        // des blocs (vers le piston), comme pour une extension.
        moveAll(event.getBlocks(), event.getDirection());
    }

    /** Un minerai posé qui saute dans une explosion quitte simplement le suivi. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) placed.consume(b);
    }

    /**
     * Déplace les marques en commençant par le bloc de tête : une marque
     * déplacée ne doit pas atterrir sur la case d'un bloc qui n'a pas encore bougé.
     */
    private void moveAll(List<Block> blocks, BlockFace direction) {
        if (blocks.isEmpty()) return;
        List<Block> ordered = new ArrayList<Block>(blocks);
        ordered.sort((a, b) -> Integer.compare(project(b, direction), project(a, direction)));
        for (Block b : ordered) placed.move(b, b.getRelative(direction));
    }

    /** Position du bloc le long de la direction du mouvement. */
    private static int project(Block b, BlockFace d) {
        return b.getX() * d.getModX() + b.getY() * d.getModY() + b.getZ() * d.getModZ();
    }
}
