package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class FoodAppleListener implements Listener {

    @EventHandler
    public void onGoldenAppleConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.GOLDEN_APPLE  /*|| Material.FRAIDE*/) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (item.getDurability() == 0) {
                    // Pomme dorée normale
                    CooldownManager.getCooldownManager().set(player, 10, CooldownType.APPLE, TimeUnit.SECONDS);
                } else if (item.getDurability() == 1) {
                    // Pomme dorée enchantée
                    CooldownManager.getCooldownManager().set(player, 20, CooldownType.GOLDENAPPLE, TimeUnit.SECONDS);
                } /* else if (item.getType() == Material.FRAISE) {
                    // Fraise
                    CooldownManager.getCooldownManager().set(player, 20, CooldownType.FRAISE, TimeUnit.SECONDS);
                } */

            }
        }.runTaskLater(OriginsFightCore.getInstance(), 20L);
    }

}



