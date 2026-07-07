package fr.originsfight.essentials.command;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;

/**
 * Socle des commandes essentials : filtre joueur-uniquement, cooldown configurable
 * par commande ({@code cooldowns.<nom>} dans essentials.yml) et isolation des erreurs.
 *
 * <p>La permission principale est déclarée dans plugin.yml (vérifiée par Bukkit
 * avant l'exécuteur) ; seules les sous-permissions ({@code .others}, {@code .unsafe}...)
 * sont vérifiées dans les commandes.
 *
 * <p>Cooldown : vérifié ici pour toutes les commandes. Il est armé au retour
 * {@code true} de {@link #execute} — sauf pour les téléportations à délai, où c'est
 * le TeleportService qui l'arme à l'arrivée effective (constructeur avec
 * {@code armCooldownOnSuccess = false}).
 */
public abstract class EssCommand implements CommandExecutor, TabCompleter {

    /** Permission qui saute tous les cooldowns de commandes. */
    public static final String COOLDOWN_BYPASS = "redconflict.cooldown.bypass";

    protected final CommandEnvironment env;
    private final String name;
    private final boolean playerOnly;
    private final boolean armCooldownOnSuccess;

    protected EssCommand(CommandEnvironment env, String name,
                         boolean playerOnly, boolean armCooldownOnSuccess) {
        this.env = env;
        this.name = name;
        this.playerOnly = playerOnly;
        this.armCooldownOnSuccess = armCooldownOnSuccess;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(Messages.ERR_PLAYER_ONLY);
            return true;
        }

        if (sender instanceof Player && env.getConfig().cooldownSeconds(name) > 0
                && !sender.hasPermission(COOLDOWN_BYPASS)) {
            long left = env.getCooldowns().remaining(((Player) sender).getUniqueId(), name);
            if (left > 0) {
                sender.sendMessage(Messages.fmt(Messages.ERR_COOLDOWN, Text.duration(left)));
                return true;
            }
        }

        boolean success;
        try {
            success = execute(sender, label, args);
        } catch (Exception e) {
            env.getPlugin().getLogger().log(Level.SEVERE, "[Essentials] Erreur dans /" + name, e);
            sender.sendMessage(Messages.ERR_INTERNAL);
            return true;
        }

        if (success && armCooldownOnSuccess && sender instanceof Player) {
            env.getCooldowns().arm(((Player) sender).getUniqueId(), name,
                    env.getConfig().cooldownSeconds(name));
        }
        return true;
    }

    /**
     * Logique de la commande (déléguée aux services métier).
     *
     * @return true si l'action a abouti (arme le cooldown le cas échéant)
     */
    protected abstract boolean execute(CommandSender sender, String label, String[] args);

    // ── Helpers communs ────────────────────────────────────────────────────────

    /** @return le joueur en ligne, ou {@code null} après avoir informé l'appelant. */
    protected Player findOnline(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Messages.ERR_PLAYER_NOT_FOUND);
            return null;
        }
        return target;
    }

    /** @return l'entier parsé, ou {@code null} après avoir informé l'appelant. */
    protected Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(Messages.ERR_INVALID_NUMBER);
            return null;
        }
    }

    /** @return le montant strictement positif (2 décimales), ou {@code null} après message. */
    protected Double parseAmount(CommandSender sender, String raw) {
        try {
            double amount = Double.parseDouble(raw.replace(',', '.'));
            if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
                sender.sendMessage(Messages.ERR_INVALID_NUMBER);
                return null;
            }
            return Math.floor(amount * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            sender.sendMessage(Messages.ERR_INVALID_NUMBER);
            return null;
        }
    }

    /**
     * Vérifie la sous-permission {@code <base>.others} quand la commande cible
     * un autre joueur. @return false après avoir informé l'appelant.
     */
    protected boolean checkOthers(CommandSender sender, String basePermission) {
        if (sender.hasPermission(basePermission + ".others")) {
            return true;
        }
        sender.sendMessage(Messages.ERR_NO_PERM_OTHERS);
        return false;
    }

    /** Par défaut : complétion Bukkit standard (noms des joueurs en ligne). */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return null;
    }
}
