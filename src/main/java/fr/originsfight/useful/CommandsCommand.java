package fr.originsfight.useful;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Commande /commands : affiche la liste de toutes les commandes joueur disponibles.
 */
public class CommandsCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player player = (Player) sender;

        player.sendMessage(RC.SEP);
        player.sendMessage(RC.PRE + "§eListe des commandes §f:");
        player.sendMessage("");
        player.sendMessage("  §6§lGénéral");
        player.sendMessage("  §8| §f/rtp         §7— Téléportation aléatoire");
        player.sendMessage("  §8| §f/ct          §7— Vérifie ton statut de combat");
        player.sendMessage("  §8| §f/ks          §7— Statistiques de combat");
        player.sendMessage("  §8| §f/baltop      §7— Classement des plus riches");
        player.sendMessage("  §8| §f/msg         §7— Message privé §8(§f/r §7pour répondre§8)");
        player.sendMessage("");
        player.sendMessage("  §6§lSocial");
        player.sendMessage("  §8| §f/friend      §7— Système d'amis §8(§fmax 5, pas de dégâts entre amis§8)");
        player.sendMessage("  §8|   §8└ §f/friend add <joueur>    §7envoyer une demande");
        player.sendMessage("  §8|   §8└ §f/friend list            §7voir vos amis");
        player.sendMessage("  §8|   §8└ §f/friend requests        §7demandes reçues");
        player.sendMessage("");
        player.sendMessage("  §6§lÉchanges & Économie");
        player.sendMessage("  §8| §f/hdv         §7— Hôtel des Ventes");
        player.sendMessage("  §8| §f/trade       §7— Échange sécurisé d'items");
        player.sendMessage("  §8| §f/prime       §7— Placer une prime sur un joueur");
        player.sendMessage("  §8| §f/loto        §7— Parier sur le loto §8(§f/loto help§8)");
        player.sendMessage("");
        player.sendMessage("  §6§lUtilitaires");
        player.sendMessage("  §8| §f/repairall   §7— Réparer tous vos items");
        player.sendMessage("  §8| §f/bottlexp    §7— Embouteiller vos niveaux d'XP");
        player.sendMessage("  §8| §f/furnace     §7— Cuire des items sans four");
        player.sendMessage("  §8| §f/poubelle    §7— Ouvrir la poubelle virtuelle");
        player.sendMessage("  §8| §f/vision      §7— Vision nocturne");
        player.sendMessage("  §8| §f/cobble      §7— Filtrage de la cobblestone");
        player.sendMessage("");
        player.sendMessage(RC.SEP);

        return true;
    }
}
