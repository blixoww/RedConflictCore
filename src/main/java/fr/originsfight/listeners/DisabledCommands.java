package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DisabledCommands implements Listener {

    /**
     * Alias vanilla Bukkit/Minecraft connus qui pointent vers des commandes sensibles.
     * Certains alias sont gérés en interne par Bukkit et ne passent pas par le nom original.
     */
    private static final Set<String> VANILLA_ALWAYS_BLOCKED = new HashSet<>(Arrays.asList(
        // Plugins / version
        "pl", "plugins", "bukkit:pl", "bukkit:plugins",
        "version", "ver", "bukkit:version",
        "about", "icanhasbukkit",
        // Help non filtrable sinon
        "help", "?",
        // TP / GameMode (à activer si besoin)
        // "tp", "teleport", "gm", "gamemode"
        // Minecraft natif
        "minecraft:pl", "minecraft:plugins",
        "spigot:plugins", "spigot:version"
    ));

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        // Extraire la commande brute : "/bukkit:plugins foo" → cmdFull="bukkit:plugins", cmdBase="plugins"
        String raw     = event.getMessage().toLowerCase().trim();
        String cmdFull = raw.split(" ")[0].substring(1); // sans le "/"
        String cmdBase = cmdFull.contains(":") ? cmdFull.split(":")[1] : cmdFull;

        // 1. Commandes TOUJOURS bloquées (config + liste interne)
        for (String disabled : OriginsFightCore.getInstance().getAlwaysDisabledCommands()) {
            String d = disabled.toLowerCase().trim();
            if (matches(cmdFull, cmdBase, d)) {
                event.setCancelled(true);
                player.sendMessage(RC.PRE + "§cVous n'avez pas la permission d'utiliser cette commande.");
                return;
            }
        }
        // Bloquer aussi les alias vanilla connus non listés dans la config
        if (VANILLA_ALWAYS_BLOCKED.contains(cmdFull) || VANILLA_ALWAYS_BLOCKED.contains(cmdBase)) {
            event.setCancelled(true);
            player.sendMessage(RC.PRE + "§cVous n'avez pas la permission d'utiliser cette commande.");
            return;
        }

        // 2. Commandes bloquées EN COMBAT
        if (CooldownManager.instance().isOnCooldown(player, CooldownType.COMBAT)) {
            for (String disabled : OriginsFightCore.getInstance().getDisabledInCombatCommands()) {
                String d = disabled.toLowerCase().trim();
                if (matches(cmdFull, cmdBase, d)) {
                    event.setCancelled(true);
                    player.sendMessage(RC.PRE + "§cImpossible d'utiliser cette commande en combat.");
                    return;
                }
            }
        }
    }

    /** Vérifie si la commande correspond à l'entrée désactivée (plein nom ou base). */
    private boolean matches(String cmdFull, String cmdBase, String disabled) {
        return cmdFull.equals(disabled) || cmdBase.equals(disabled);
    }
}
