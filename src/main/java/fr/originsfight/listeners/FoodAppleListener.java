package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import fr.originsfight.utils.TimeUnits;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

public class FoodAppleListener implements Listener {

    @EventHandler
    public void onGoldenAppleConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null) return;

        if (item.getType() == Material.GOLDEN_APPLE) {
            if (item.getDurability() == 0) {
                if (CooldownManager.instance().isOnCooldown(player, CooldownType.APPLE)) {
                    long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.APPLE);
                    player.sendMessage("§cTu dois attendre " + CooldownManager.getFormattedTimeLeft(timeLeft) + " avant de pouvoir manger une pomme en or.");
                    event.setCancelled(true);
                    return;
                }
                CooldownManager.instance().set(player, 10, TimeUnits.SECONDS, CooldownType.APPLE);
            }
            else if (item.getDurability() == 1) {
                if (CooldownManager.instance().isOnCooldown(player, CooldownType.GOLDENAPPLE)) {
                    long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.GOLDENAPPLE);
                    player.sendMessage("§cTu dois attendre " + CooldownManager.getFormattedTimeLeft(timeLeft) + " avant de pouvoir manger une pomme enchantée.");
                    event.setCancelled(true);
                    return;
                }
                CooldownManager.instance().set(player, 20, TimeUnits.SECONDS, CooldownType.GOLDENAPPLE);
            }
        }

        /*if (item.getType() == Material.FRAISE) {
            if (CooldownManager.instance().isOnCooldown(player, CooldownType.FRAISE)) {
                long timeLeft = CooldownManager.instance().timeLeft(player, CooldownType.FRAISE);
                player.sendMessage("§cTu dois attendre " + CooldownManager.getFormattedTimeLeft(timeLeft) + " avant de pouvoir manger une fraise.");
                event.setCancelled(true);
                return;
            }
            CooldownManager.instance().set(player, 15, TimeUnits.SECONDS, CooldownType.FRAISE);
        } */
    }
}
