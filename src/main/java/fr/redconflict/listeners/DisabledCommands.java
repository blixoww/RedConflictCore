package fr.redconflict.listeners;

import fr.redconflict.core.text.RC;
import fr.redconflict.cooldown.CooldownManager;
import fr.redconflict.cooldown.CooldownType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Blocage de commandes pour les non-OP : listes de config.yml
 * ({@code commands.always-disabled} et {@code commands.disabled-in-combat},
 * rechargeables via /red reload) plus les alias vanilla sensibles que Bukkit
 * gère en interne et qui échapperaient au filtrage par nom.
 */
public class DisabledCommands implements Listener {

    private static final Set<String> VANILLA_ALWAYS_BLOCKED = new HashSet<>(Arrays.asList(
            "pl", "plugins", "bukkit:pl", "bukkit:plugins",
            "version", "ver", "bukkit:version",
            "about", "icanhasbukkit",
            "help", "?",
            "minecraft:pl", "minecraft:plugins",
            "spigot:plugins", "spigot:version"));

    private final GameplayRulesModule rules;

    public DisabledCommands(GameplayRulesModule rules) {
        this.rules = rules;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            return;
        }

        // "/bukkit:plugins foo" → cmdFull = "bukkit:plugins", cmdBase = "plugins"
        String cmdFull = event.getMessage().toLowerCase().trim().split(" ")[0].substring(1);
        String cmdBase = cmdFull.contains(":") ? cmdFull.split(":")[1] : cmdFull;

        if (isListed(cmdFull, cmdBase, rules.getAlwaysDisabledCommands())
                || VANILLA_ALWAYS_BLOCKED.contains(cmdFull) || VANILLA_ALWAYS_BLOCKED.contains(cmdBase)) {
            event.setCancelled(true);
            player.sendMessage(RC.ERR_NO_PERM);
            return;
        }

        if (CooldownManager.instance().isOnCooldown(player, CooldownType.COMBAT)
                && isListed(cmdFull, cmdBase, rules.getDisabledInCombatCommands())) {
            event.setCancelled(true);
            player.sendMessage(RC.ERR_IN_COMBAT);
        }
    }

    private boolean isListed(String cmdFull, String cmdBase, Iterable<String> disabledList) {
        for (String disabled : disabledList) {
            String d = disabled.toLowerCase().trim();
            if (cmdFull.equals(d) || cmdBase.equals(d)) {
                return true;
            }
        }
        return false;
    }
}
