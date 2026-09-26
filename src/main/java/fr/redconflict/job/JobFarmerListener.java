package fr.redconflict.job;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Donne de l'XP Agriculteur pour la récolte et la plantation.
 *
 * <p><b>Les plantes qui poussent en colonne</b> (canne à sucre, cactus) et le
 * melon se posent sous la forme même qu'on casse. Sans garde-fou, une seule
 * canne suffisait à monter le métier à l'infini : poser (XP de plantation),
 * casser (XP de récolte), ramasser, recommencer. D'où deux règles :
 * <ul>
 *   <li>un bloc <b>posé à la main</b> ne rapporte rien quand on le casse — seuls
 *       les segments nés d'une pousse comptent comme récolte ;</li>
 *   <li>l'XP de plantation n'est versée qu'à la <b>première pousse</b> au-dessus
 *       du bloc posé : planter pour de vrai paie toujours, poser-casser non.</li>
 * </ul>
 * <p><b>Les cultures</b> (blé, carottes, pommes de terre, pieds de melon) ne
 * rapportent leur XP de récolte qu'à maturité ({@code :7}), mais leur XP de
 * PLANTATION tombait à la pose : planter une graine, la casser aussitôt (elle
 * se ramasse), replanter… en boucle. Elle est désormais versée quand la
 * culture atteint sa maturité — planter pour de vrai paie toujours.
 *
 * <p>Suivi en mémoire : après un redémarrage, une base posée avant peut
 * rapporter une récolte une fois. Borné à {@link #MAX_TRACKED} emplacements
 * pour qu'un bloc emporté par un piston ou l'eau ne s'accumule pas sans fin.
 */
public class JobFarmerListener implements Listener {

    private static final int MAX_TRACKED = 200_000;

    private final JobManager manager;
    private final JobConfig  config;

    /** Emplacement d'un bloc posé à la main → joueur qui l'a posé. */
    private final Map<String, UUID> placedByHand = new LinkedHashMap<String, UUID>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public JobFarmerListener(JobManager manager, JobConfig config) {
        this.manager = manager;
        this.config  = config;
    }

    /** Blocs qui se posent sous la forme qu'on récolte, et donc se bouclent. */
    private static boolean isLoopable(Material mat) {
        return mat == Material.SUGAR_CANE_BLOCK || mat == Material.CACTUS || mat == Material.MELON_BLOCK;
    }

    /** Cultures dont la plantation ne paie qu'à maturité (données 7). */
    private static boolean isCrop(Material mat) {
        return mat == Material.CROPS || mat == Material.CARROT || mat == Material.POTATO
            || mat == Material.MELON_STEM || mat == Material.PUMPKIN_STEM;
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ':' + l.getBlockX() + ':' + l.getBlockY() + ':' + l.getBlockZ();
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material mat = block.getType();

        // Posé à la main : aucune XP de récolte.
        if (isLoopable(mat) && placedByHand.remove(key(block.getLocation())) != null) return;
        // Culture cassée : sa plantation ne sera jamais payée (elle ne mûrira plus).
        if (isCrop(mat)) placedByHand.remove(key(block.getLocation()));

        int data = block.getData();
        int level = manager.getLevel(player, JobType.FARMER);
        int xp = config.getFarmerXp("break", mat.name() + ":" + data, level);
        if (xp == 0) xp = config.getFarmerXp("break", mat.name(), level);
        if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material mat = block.getType();

        if (isLoopable(mat) || isCrop(mat)) {
            // XP de plantation différée à la première pousse / à la maturité (voir onGrow).
            placedByHand.put(key(block.getLocation()), player.getUniqueId());
            return;
        }

        int level = manager.getLevel(player, JobType.FARMER);
        int xp = config.getFarmerXp("place", mat.name() + ":" + block.getData(), level);
        if (xp == 0) xp = config.getFarmerXp("place", mat.name(), level);
        if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
    }

    /**
     * Un segment de canne ou de cactus vient de pousser : si la base a été posée
     * à la main, son planteur touche enfin son XP de plantation — une seule fois,
     * la base restant marquée « posée » pour la récolte.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        Material grown = event.getNewState().getType();
        if (isCrop(grown)) {
            // Maturité atteinte : la plantation est enfin payée, une fois.
            if (event.getNewState().getRawData() < 7) return;
            UUID planter = placedByHand.remove(key(event.getBlock().getLocation()));
            if (planter == null || PAID.equals(planter)) return;
            Player player = Bukkit.getPlayer(planter);
            if (player == null) return;
            int xp = config.getFarmerXp("place", grown.name(), manager.getLevel(player, JobType.FARMER));
            if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
            return;
        }
        if (grown != Material.SUGAR_CANE_BLOCK && grown != Material.CACTUS) return;

        // Remonte la colonne jusqu'à la base (3 blocs au plus en vanilla).
        Block below = event.getBlock().getRelative(BlockFace.DOWN);
        while (below.getType() == grown && below.getRelative(BlockFace.DOWN).getType() == grown) {
            below = below.getRelative(BlockFace.DOWN);
        }
        String baseKey = key(below.getLocation());
        UUID planter = placedByHand.get(baseKey);
        if (planter == null) return;
        // Marqueur « déjà payé » : même clé, valeur nulle interdite → on remplace
        // par un UUID sentinelle qui ne correspond à aucun joueur.
        if (PAID.equals(planter)) return;
        placedByHand.put(baseKey, PAID);

        Player player = Bukkit.getPlayer(planter);
        if (player == null) return;   // planteur hors ligne : XP de plantation perdue
        int level = manager.getLevel(player, JobType.FARMER);
        int xp = config.getFarmerXp("place", grown.name(), level);
        if (xp > 0) manager.giveXp(player, JobType.FARMER, xp);
    }

    private static final UUID PAID = new UUID(0L, 0L);
}
