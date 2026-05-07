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

        player.sendMessage("  §c§l⚔ Combat & Duels");
        player.sendMessage("  §8| §f/duel <joueur>       §7— Défier un joueur §8(votre équipement)");
        player.sendMessage("  §8| §f/duelk <joueur>      §7— Défier un joueur §8(kit défini)");
        player.sendMessage("  §8| §f/duelrandom          §7— Duel aléatoire §8(votre équipement)");
        player.sendMessage("  §8| §f/duelkrandom         §7— Duel aléatoire §8(kit défini)");
        player.sendMessage("");

        player.sendMessage("  §e§l★ Stats & Profil");
        player.sendMessage("  §8| §f/ks                  §7— Kills, morts, ratio, temps de jeu");
        player.sendMessage("  §8| §f/profil <joueur>     §7— Voir le profil complet d'un joueur");
        player.sendMessage("");

        player.sendMessage("  §6§l$ Économie");
        player.sendMessage("  §8| §f/hdv                 §7— Hôtel des Ventes §8(achat/vente)");
        player.sendMessage("  §8| §f/shop                §7— Boutique du serveur");
        player.sendMessage("  §8| §f/baltop              §7— Classement des plus riches");
        player.sendMessage("  §8| §f/prime <joueur> <$>  §7— Placer une prime §8(§f/prime list§8)");
        player.sendMessage("  §8| §f/loto <montant>      §7— Parier sur le loto §8(§f/loto help§8)");
        player.sendMessage("");

        player.sendMessage("  §a§l♥ Social");
        player.sendMessage("  §8| §f/friend add <joueur> §7— Ajouter un ami §8(pas de dégâts mutuels)");
        player.sendMessage("  §8| §f/friend list         §7— Voir vos amis");
        player.sendMessage("  §8| §f/friend requests     §7— Demandes d'amis reçues");
        player.sendMessage("  §8| §f/trade <joueur>      §7— Échange sécurisé d'items");
        player.sendMessage("");

        player.sendMessage("  §d§l✦ Événements");
        player.sendMessage("  §8| §f/plannings           §7— Voir les prochains événements");
        player.sendMessage("");

        player.sendMessage("  §7§lUtilitaires");
        player.sendMessage("  §8| §f/repairall           §7— Réparer tous vos items §8(cooldown 24h)");
        player.sendMessage("  §8| §f/cobble              §7— Filtrer la cobblestone automatiquement");
        player.sendMessage("  §8| §f/furnace this|all    §7— Cuire des items sans four");
        player.sendMessage("  §8| §f/bottlexp            §7— Embouteiller vos niveaux d'XP");
        player.sendMessage("  §8| §f/poubelle            §7— Poubelle virtuelle");
        player.sendMessage("  §8| §f/vision              §7— Vision nocturne");
        player.sendMessage("");

        player.sendMessage(RC.SEP);

        return true;
    }
}
