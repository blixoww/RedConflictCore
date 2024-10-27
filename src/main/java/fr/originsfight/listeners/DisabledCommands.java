package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class DisabledCommands implements Listener {

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase();

        if (!player.isOp()) {
            for (String command : OriginsFightCore.getInstance().getAlwaysDisabledCommands()) {
                if (message.equals("/" + command) || message.startsWith("/" + command + " ")) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (CooldownManager.instance().isOnCooldown(player, CooldownType.COMBAT)) {
                for (String command : OriginsFightCore.getInstance().getDisabledInCombatCommands()) {
                    if (message.equals("/" + command) || message.startsWith("/" + command + " ")) {
                        event.setCancelled(true);
                        player.sendMessage("§cCette commande est désactivée pendant un combat.");
                        return;
                    }
                }
            }
        }
    }
}
