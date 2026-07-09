package fr.redconflict.xpboost;

import fr.redconflict.core.text.RC;
import fr.redconflict.packets.CustomPacketServerHandler;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Active le boost d'XP x2 quand un joueur fait un clic droit avec l'item
 * {@code xp_booster} (id NMS {@value XpBoostManager#ITEM_ID}).
 * <p>
 * Règles :
 * <ul>
 *   <li>Un seul exemplaire est consommé par activation (jamais le stack entier).</li>
 *   <li>Si un boost est déjà actif, la potion <b>n'est pas consommée</b> et le
 *       joueur est informé du temps restant.</li>
 * </ul>
 */
public class XpBoostListener implements Listener {

    /** Un clic droit maintenu répète l'event chaque tick (~50 ms). Pour ne traiter
     *  qu'un seul exemplaire par clic physique (et ne pas spammer le message « déjà
     *  actif »), on ne réagit qu'au front montant : un nouvel appui est détecté
     *  quand l'intervalle depuis le dernier event dépasse ce seuil. */
    private static final long NEW_PRESS_GAP_MS = 250L;

    private final XpBoostManager manager;
    /** Optionnel : pour pousser un JOB_DATA frais au client après activation. */
    private final fr.redconflict.job.JobManager jobManager;
    /** Dernier instant où l'item a été cliqué (mis à jour à chaque event). */
    private final ConcurrentHashMap<UUID, Long> lastEvent = new ConcurrentHashMap<>();

    public XpBoostListener(XpBoostManager manager) {
        this(manager, null);
    }

    public XpBoostListener(XpBoostManager manager, fr.redconflict.job.JobManager jobManager) {
        this.manager = manager;
        this.jobManager = jobManager;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack inHand = player.getItemInHand();
        if (inHand == null) return;
        if (CustomPacketServerHandler.getNmsItemId(inHand) != XpBoostManager.ITEM_ID) return;

        // Empêche tout comportement vanilla et la double-activation main/off
        event.setCancelled(true);

        // Front montant uniquement : on ignore les répétitions d'un clic maintenu.
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long prev = lastEvent.put(uuid, now);
        if (prev != null && now - prev < NEW_PRESS_GAP_MS) return;

        // Déjà boosté → on ne consomme pas la potion, on informe le joueur.
        if (manager.isActive(uuid)) {
            long remaining = manager.getRemainingMs(uuid);
            player.sendMessage(RC.PRE + "§cTu as déjà un boost d'XP §f§lx2 §cactif !");
            player.sendMessage(RC.PRE + "§7Temps restant : §e" + formatDuration(remaining));
            try { player.playSound(player.getLocation(), Sound.NOTE_BASS, 1f, 0.8f); } catch (Throwable ignored) {}
            return;
        }

        // Consomme exactement un exemplaire.
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
            player.setItemInHand(inHand);
        } else {
            player.setItemInHand(null);
        }
        player.updateInventory();

        long remaining = manager.activate(player);
        // Rafraîchit le menu métiers (affiche le compte à rebours du boost).
        if (jobManager != null) jobManager.resendJobData(player);
        player.sendMessage(RC.PRE + "§a✔ Boost d'XP §f§lx2 §aactivé sur les métiers !");
        player.sendMessage(RC.PRE + "§7Temps restant : §e" + formatDuration(remaining));
        try { player.playSound(player.getLocation(), Sound.LEVEL_UP, 1f, 1.6f); } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        manager.unload(uuid);
        lastEvent.remove(uuid);
    }

    /** Formate une durée en ms vers "Xh Ymin" / "Ymin" / "Zs". */
    static String formatDuration(long ms) {
        long h   = TimeUnit.MILLISECONDS.toHours(ms);
        long min = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long s   = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (h > 0)   return h + "h " + min + "min";
        if (min > 0) return min + "min";
        return s + "s";
    }
}
