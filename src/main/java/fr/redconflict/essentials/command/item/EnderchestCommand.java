package fr.redconflict.essentials.command.item;

import fr.redconflict.core.text.Text;
import fr.redconflict.db.ItemArrayCodec;
import fr.redconflict.db.PlayerDataDatabase;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.model.SeenRecord;
import fr.redconflict.essentials.service.InvseeSessions;
import fr.redconflict.essentials.service.SeenService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * /ec [joueur] — ouvre son enderchest ; celui d'un autre joueur avec
 * la sous-permission {@code redconflict.ec.others}.
 *
 * <p><b>Le joueur visé peut être hors ligne.</b> Son enderchest est alors relu
 * dans {@code player_data}, la table que la synchronisation entre serveurs
 * écrit à chaque déconnexion et toutes les quelques minutes. C'est un
 * <b>instantané</b>, et il est présenté comme tel : lecture seule, avec la date
 * de la dernière sauvegarde.
 *
 * <p>Cette date n'est pas un détail : un joueur absent d'<i>ici</i> peut très
 * bien jouer sur un autre serveur de la grappe, où il remplit son coffre sans
 * que cette ligne bouge avant la prochaine sauvegarde automatique. On affiche
 * donc l'ancienneté plutôt que d'affirmer « hors ligne », et le staff juge.
 *
 * <p><b>Pourquoi la lecture seule.</b> Ce qu'on affiche est une copie, pas le
 * coffre du joueur. Autoriser les modifications demanderait de réécrire la
 * ligne à la fermeture — et si le joueur se reconnecte entre-temps, sa propre
 * sauvegarde écraserait celle du staff, ou l'inverse. Une modification qui
 * disparaît sans rien dire est bien pire qu'une modification impossible. La
 * consultation, elle, est sans risque, et c'est ce qu'on vient chercher :
 * vérifier ce qu'un joueur a mis de côté.
 */
public class EnderchestCommand extends EssCommand {

    /** Taille d'un enderchest — la même que celle stockée en base. */
    private static final int ENDER_SIZE = 27;

    private final SeenService seen;
    private final InvseeSessions sessions;
    private final PlayerDataDatabase data;

    public EnderchestCommand(CommandEnvironment env, SeenService seen,
                             InvseeSessions sessions, PlayerDataDatabase data) {
        super(env, "ec", true, true);
        this.seen = seen;
        this.sessions = sessions;
        this.data = data;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (args.length == 0) {
            player.openInventory(player.getEnderChest());
            return true;
        }

        if (!checkOthers(player, "redconflict.ec")) return false;

        // En ligne : on ouvre le vrai coffre, modifiable comme avant.
        Player target = Bukkit.getPlayer(args[0]);
        if (target != null) {
            player.openInventory(target.getEnderChest());
            player.sendMessage(Text.info("Enderchest de §f" + target.getName() + "§7."));
            return true;
        }

        return openOffline(player, args[0]);
    }

    /** Ouvre l'instantané en base d'un joueur hors ligne, en lecture seule. */
    private boolean openOffline(Player player, String name) {
        UUID uuid = seen.resolveUuid(name);
        if (uuid == null) {
            player.sendMessage(Text.error("Joueur §f" + name + " §cinconnu (jamais connecté)."));
            return false;
        }

        // Nom tel qu'il a été vu la dernière fois : la casse saisie par le staff
        // n'est pas forcément la bonne, et un titre « EC de vAlEnTiN » ferait
        // douter de la cible.
        SeenRecord record = seen.findByName(name);
        String display = record != null ? record.getName() : name;

        PlayerDataDatabase.PlayerData snapshot = data.load(uuid);
        if (snapshot == null) {
            // Deux cas sous un seul message, faute de pouvoir les distinguer :
            // synchronisation désactivée, ou joueur jamais revenu depuis qu'elle
            // est active. Dans les deux cas il n'y a rien à montrer, et inventer
            // un coffre vide laisserait croire qu'il l'est.
            player.sendMessage(Text.error("Aucune donnée enregistrée pour §f" + display + "§c."));
            player.sendMessage(Text.info("La synchronisation doit être active et le joueur "
                    + "s'être connecté au moins une fois depuis."));
            return false;
        }

        Inventory view = Bukkit.createInventory(null, ENDER_SIZE, "EC de " + display);
        ItemStack[] stored = ItemArrayCodec.decode(snapshot.ender);
        if (stored != null) {
            // Recopie borné des deux côtés : une ligne écrite par une version
            // antérieure peut porter un nombre de slots différent, et on ne veut
            // ni exception ni troncature silencieuse du coffre affiché.
            for (int slot = 0; slot < Math.min(stored.length, ENDER_SIZE); slot++) {
                view.setItem(slot, stored[slot]);
            }
        }

        // Même mécanique que /invsee : la session rend tous les clics inertes
        // jusqu'à la fermeture de l'inventaire.
        //
        // Ouvrir AVANT d'enregistrer la session : openInventory ferme ce que le
        // joueur avait sous les yeux, et cette fermeture déclenche le
        // InventoryCloseEvent qui efface les sessions. Dans l'autre ordre, on
        // effacerait la session qu'on vient d'ouvrir et le coffre deviendrait
        // modifiable sans que rien ne le signale.
        player.openInventory(view);
        sessions.open(player.getUniqueId(), uuid);

        // « Instantané » et non « hors ligne » : le joueur peut très bien être
        // connecté sur un autre serveur de la grappe, où le Bukkit local ne le
        // voit pas. Ce qui compte est dit juste en dessous — de quand datent ces
        // objets.
        player.sendMessage(Text.info("Enderchest de §f" + display
                + " §7(instantané, lecture seule)."));
        player.sendMessage(Text.info("Dernière sauvegarde : §f"
                + (snapshot.updatedAt > 0 ? Text.since(snapshot.updatedAt) : "inconnue") + "§7."));
        return true;
    }
}
