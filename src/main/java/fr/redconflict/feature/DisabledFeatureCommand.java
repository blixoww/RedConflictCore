package fr.redconflict.feature;

import fr.redconflict.core.text.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

/**
 * Exécuteur attribué aux commandes dont la fonctionnalité a été désactivée via la config
 * ({@code features.<clé>: false}). Il informe le joueur que la commande n'est pas disponible
 * sur ce serveur et ne fait rien d'autre.
 *
 * <p>Instance unique partagée : un même objet peut être attribué à plusieurs commandes désactivées.
 */
public class DisabledFeatureCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(RC.PRE + "§cCette fonctionnalité est désactivée sur ce serveur.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
