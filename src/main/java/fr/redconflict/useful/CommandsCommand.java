package fr.redconflict.useful;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** /commands — liste des commandes joueur, groupées par thème. */
public class CommandsCommand extends CoreCommand {

    private static final String[] HELP = {
            RC.SEP,
            RC.PRE + "§eListe des commandes §f:",
            "",
            "  §c§l⚔ §cCombat §8& §cDuels",
            "  §8| §f/duel §7<joueur> §8— §7Défier en duel §8(votre équipement)",
            "  §8| §f/duelk §7<joueur> §8— §7Défier en duel §8(kit défini)",
            "  §8| §f/duelrandom §8— §7Duel aléatoire §8(votre équipement)",
            "  §8| §f/duelkrandom §8— §7Duel aléatoire §8(kit défini)",
            "",
            "  §e§l★ §eStats §8& §eProfil",
            "  §8| §f/ks §8— §7Kills, morts, ratio, temps de jeu",
            "  §8| §f/profil §7<joueur> §8— §7Voir le profil complet d'un joueur",
            "",
            "  §6§l$ §6Économie",
            "  §8| §f/hdv §8— §7Hôtel des Ventes §8(achat/vente)",
            "  §8| §f/shop §8— §7Boutique du serveur",
            "  §8| §f/sellall §8— §7Vendre tous les items vendables §8(raccourci rapide)",
            "  §8| §f/baltop §8— §7Classement des plus riches",
            "  §8| §f/prime §7<joueur> <$> §8— §7Placer une prime §8(§f/prime list§8)",
            "  §8| §f/loto §7<montant> §8— §7Parier sur le loto §8(§f/loto help§8)",
            "",
            "  §b§l⛏ §bMétiers",
            "  §8| §f/metier §8— §7Ouvrir l'interface de métier §8(Mineur / Agriculteur / Artisan)",
            "  §8| §f/metier top §8— §7Classement global des métiers",
            "  §8| §f/metier top §7<metier> §8— §7Classement par métier",
            "",
            "  §a§l♥ §aSocial",
            "  §8| §f/friend add §7<joueur> §8— §7Ajouter un ami §8(pas de dégâts mutuels)",
            "  §8| §f/friend list §8— §7Voir vos amis",
            "  §8| §f/friend requests §8— §7Demandes d'amis reçues",
            "  §8| §f/trade §7<joueur> §8— §7Échange sécurisé d'items",
            "",
            "  §d§l✦ §dÉvénements",
            "  §8| §f/plannings §8— §7Voir les prochains événements",
            "",
            "  §9§l⇆ §9Serveurs",
            "  §8| §f/hub §8— §7Rejoindre le serveur HUB §8(lobby)",
            "  §8| §f/faction §8— §7Rejoindre le serveur Faction",
            "  §8| §f/minage §8— §7Rejoindre le serveur Minage",
            "",
            "  §7§lUtilitaires",
            "  §8| §f/repairall §8— §7Réparer tous vos items §8(cooldown 24h)",
            "  §8| §f/cobble §8— §7Filtrer la cobblestone automatiquement",
            "  §8| §f/furnace §7<this|all> §8— §7Cuire des items sans four",
            "  §8| §f/bottlexp §8— §7Embouteiller vos niveaux d'XP",
            "  §8| §f/poubelle §8— §7Poubelle virtuelle",
            "  §8| §f/vision §8— §7Vision nocturne",
            "  §8| §f/guide §8— §7Ouvrir le guide de craft",
            "",
            RC.SEP
    };

    public CommandsCommand(JavaPlugin plugin) {
        super(plugin, "commands", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        for (String line : HELP) {
            sender.sendMessage(line);
        }
    }
}
