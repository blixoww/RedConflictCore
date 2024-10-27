package fr.originsfight.combatlog;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import fr.originsfight.utils.TimeUnits;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class CombatLogListener implements Listener {

    @EventHandler
    public void onDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        Player damager = (Player) event.getDamager();
        if (!player.isOp()) {
            if (CooldownManager.instance().timeLeft(player, CooldownType.COMBAT) == 0)
                player.sendMessage("§7Tu viens d'entrer en combat.");
            CooldownManager.instance().set(player, 30, TimeUnits.SECONDS, CooldownType.COMBAT);
        }
        if (!damager.isOp()) {
            if (CooldownManager.instance().timeLeft(damager, CooldownType.COMBAT) == 0)
                damager.sendMessage("§7Tu viens d'entrer en combat.");
            CooldownManager.instance().set(damager, 30, TimeUnits.SECONDS, CooldownType.COMBAT);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (CooldownManager.instance().timeLeft(player, CooldownType.COMBAT) > 0) {
            player.setHealth(0);
            Bukkit.broadcastMessage("§c" + player.getName() + " est mort suite à un combat log.");
        }
    }

}
