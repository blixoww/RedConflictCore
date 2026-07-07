package fr.originsfight.core.command;

import fr.originsfight.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * Socle des commandes du plugin : filtre joueur-uniquement, isolation des
 * erreurs et helpers de parsing partagés. Même modèle que le socle essentials
 * ({@code EssCommand}), sans les cooldowns configurables — les domaines qui en
 * ont gèrent les leurs (voir {@code cooldown.CooldownManager}).
 *
 * <p>La permission principale est déclarée dans plugin.yml (vérifiée par Bukkit
 * avant l'exécuteur) ; seules les sous-permissions sont vérifiées en commande.
 */
public abstract class CoreCommand implements CommandExecutor, TabCompleter {

    protected final JavaPlugin plugin;
    private final String name;
    private final boolean playerOnly;

    protected CoreCommand(JavaPlugin plugin, String name, boolean playerOnly) {
        this.plugin = plugin;
        this.name = name;
        this.playerOnly = playerOnly;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        try {
            execute(sender, label, args);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Commands] Erreur dans /" + name, e);
            sender.sendMessage(RC.ERR_INTERNAL);
        }
        return true;
    }

    /** Logique de la commande (déléguée aux managers/services du domaine). */
    protected abstract void execute(CommandSender sender, String label, String[] args);

    // ── Helpers communs ────────────────────────────────────────────────────────

    /** @return le joueur en ligne, ou {@code null} après avoir informé l'appelant. */
    protected Player findOnline(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(RC.ERR_PLAYER_NOT_FOUND);
            return null;
        }
        return target;
    }

    /** @return le montant entier strictement positif, ou {@code null} après message. */
    protected Long parsePositiveLong(CommandSender sender, String raw) {
        try {
            long amount = Long.parseLong(raw);
            if (amount <= 0) {
                sender.sendMessage(RC.ERR_INVALID_AMOUNT);
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            sender.sendMessage(RC.ERR_INVALID_AMOUNT);
            return null;
        }
    }

    /** Par défaut : complétion Bukkit standard (noms des joueurs en ligne). */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return null;
    }
}
