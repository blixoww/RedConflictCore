package fr.originsfight.listeners;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.TimeUnit;

public class FoodAppleListener implements Listener {

    @EventHandler
    public void onGoldenAppleConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();

        ItemStack item = event.getItem();
        if (item == null) return;

        if (item.getType() == Material.GOLDEN_APPLE && item.getDurability() == 0) {
            CooldownManager.getCooldownManager().set(player, 10, CooldownType.APPLE, TimeUnit.SECONDS);
        } else if (item.getType() == Material.GOLDEN_APPLE && item.getDurability() == 1) {
            CooldownManager.getCooldownManager().set(player, 20, CooldownType.GOLDENAPPLE, TimeUnit.SECONDS);
//        } else if (item.getType() == Material.FRAISE) {
//            CooldownManager.getCooldownManager().set(player, 30, CooldownType.FRAISE, TimeUnit.SECONDS);
        }

        if (CooldownManager.getCooldownManager().isOnCooldown(player, CooldownType.APPLE)) {
            long remaining = CooldownManager.getCooldownManager().remainingTime(player, CooldownType.APPLE);
            player.sendMessage("Vous devez attendre " + CooldownManager.formatedTime(remaining) + " avant de pouvoir consommer une pomme d'or.");
            event.setCancelled(true);
        } else if (CooldownManager.getCooldownManager().isOnCooldown(player, CooldownType.GOLDENAPPLE)) {
            long remaining = CooldownManager.getCooldownManager().remainingTime(player, CooldownType.GOLDENAPPLE);
            player.sendMessage("Vous devez attendre " + CooldownManager.formatedTime(remaining) + " avant de pouvoir consommer une pomme d'or enchanté.");
            event.setCancelled(true);
        } else if (CooldownManager.getCooldownManager().isOnCooldown(player, CooldownType.FRAISE)) {
            long remaining = CooldownManager.getCooldownManager().remainingTime(player, CooldownType.FRAISE);
            player.sendMessage("Vous devez attendre " + CooldownManager.formatedTime(remaining) + " avant de pouvoir consommer une fraise.");
            event.setCancelled(true);
        }
    }

}
