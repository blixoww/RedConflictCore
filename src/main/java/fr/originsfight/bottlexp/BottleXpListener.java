package fr.originsfight.bottlexp;

import fr.originsfight.core.text.RC;
import fr.originsfight.core.text.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/** Consommation des bouteilles d'XP custom au clic droit (rend les niveaux stockés). */
public class BottleXpListener implements Listener {

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack inHand = player.getItemInHand();
        int levels = BottleXpItem.getLevels(inHand);
        if (levels <= 0) {
            return;
        }

        // Annule le lancer vanilla de l'EXP_BOTTLE avant de consommer la fiole.
        event.setCancelled(true);
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else {
            player.setItemInHand(null);
        }
        player.setLevel(player.getLevel() + levels);
        player.sendMessage(Text.fmt(RC.BXP_RESTORED, levels));
    }
}
