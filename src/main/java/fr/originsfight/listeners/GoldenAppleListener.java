package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.UUID;

public class GoldenAppleListener implements Listener {

    private final OriginsFightCore plugin;

    public GoldenAppleListener(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onGoldenAppleConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String appleType;

        if (event.getItem().getType().name().equals("GOLDEN_APPLE")) {
            appleType = "golden_apple_normal";
        } else if (event.getItem().getType().name().equals("ENCHANTED_GOLDEN_APPLE")) {
            appleType = "golden_apple_enchanted";
        } else {
            return; // Ce n'est ni une pomme dorée normale ni enchantée
        }

        // Vérifie le cooldown pour ce type spécifique de pomme
        if (CooldownManager.isOnCooldown(playerId, appleType)) {
            int remaining = CooldownManager.getRemainingTime(playerId, appleType);
            player.sendMessage("Vous devez attendre encore " + remaining + " secondes pour consommer une autre pomme " + (appleType.contains("enchanted") ? "enchantée" : "normale") + ".");
            event.setCancelled(true);
            return;
        }

        // Applique le cooldown spécifique
        int cooldown = plugin.getConfig().getInt("golden_apple." + (appleType.contains("enchanted") ? "enchanted" : "normal") + ".cooldown");
        CooldownManager.setCooldown(playerId, appleType, cooldown);
        player.sendMessage("Cooldown appliqué pour les pommes " + (appleType.contains("enchanted") ? "enchantées" : "normales") + ".");
    }
}
