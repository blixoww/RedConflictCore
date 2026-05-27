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

        player.sendMessage("  §c§l⚔ §cCombat §8& §cDuels");
        player.sendMessage("  §8| §f/duel §7<joueur> §8— §7Défier en duel §8(votre équipement)");
        player.sendMessage("  §8| §f/duelk §7<joueur> §8— §7Défier en duel §8(kit défini)");
        player.sendMessage("  §8| §f/duelrandom §8— §7Duel aléatoire §8(votre équipement)");
        player.sendMessage("  §8| §f/duelkrandom §8— §7Duel aléatoire §8(kit défini)");
        player.sendMessage("");

        player.sendMessage("  §e§l★ §eStats §8& §eProfil");
        player.sendMessage("  §8| §f/ks §8— §7Kills, morts, ratio, temps de jeu");
        player.sendMessage("  §8| §f/profil §7<joueur> §8— §7Voir le profil complet d'un joueur");
        player.sendMessage("");

        player.sendMessage("  §6§l$ §6Économie");
        player.sendMessage("  §8| §f/hdv §8— §7Hôtel des Ventes §8(achat/vente)");
        player.sendMessage("  §8| §f/shop §8— §7Boutique du serveur");
        player.sendMessage("  §8| §f/sellall §8— §7Vendre tous les items vendables §8(raccourci rapide)");
        player.sendMessage("  §8| §f/baltop §8— §7Classement des plus riches");
        player.sendMessage("  §8| §f/prime §7<joueur> <$> §8— §7Placer une prime §8(§f/prime list§8)");
        player.sendMessage("  §8| §f/loto §7<montant> §8— §7Parier sur le loto §8(§f/loto help§8)");
        player.sendMessage("");

        player.sendMessage("  §b§l⛏ §bMétiers");
        player.sendMessage("  §8| §f/metier §8— §7Ouvrir l'interface de métier §8(Mineur / Agriculteur / Artisan)");
        player.sendMessage("  §8| §f/metier top §8— §7Classement global des métiers");
        player.sendMessage("  §8| §f/metier top §7<metier> §8— §7Classement par métier");
        player.sendMessage("");

        player.sendMessage("  §a§l♥ §aSocial");
        player.sendMessage("  §8| §f/friend add §7<joueur> §8— §7Ajouter un ami §8(pas de dégâts mutuels)");
        player.sendMessage("  §8| §f/friend list §8— §7Voir vos amis");
        player.sendMessage("  §8| §f/friend requests §8— §7Demandes d'amis reçues");
        player.sendMessage("  §8| §f/trade §7<joueur> §8— §7Échange sécurisé d'items");
        player.sendMessage("");

        player.sendMessage("  §d§l✦ §dÉvénements");
        player.sendMessage("  §8| §f/plannings §8— §7Voir les prochains événements");
        player.sendMessage("");

        player.sendMessage("  §7§lUtilitaires");
        player.sendMessage("  §8| §f/repairall §8— §7Réparer tous vos items §8(cooldown 24h)");
        player.sendMessage("  §8| §f/cobble §8— §7Filtrer la cobblestone automatiquement");
        player.sendMessage("  §8| §f/furnace §7<this|all> §8— §7Cuire des items sans four");
        player.sendMessage("  §8| §f/bottlexp §8— §7Embouteiller vos niveaux d'XP");
        player.sendMessage("  §8| §f/poubelle §8— §7Poubelle virtuelle");
        player.sendMessage("  §8| §f/vision §8— §7Vision nocturne");
        player.sendMessage("  §8| §f/guide §8— §7Ouvrir le guide de craft");
        player.sendMessage("");
        player.sendMessage(RC.SEP);

        return true;
    }
}
