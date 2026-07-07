package fr.originsfight.friend;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Commande /friend
 *
 * Sous-commandes :
 *   /friend add <joueur>     – envoyer une demande d'ami
 *   /friend accept <joueur>  – accepter une demande
 *   /friend deny <joueur>    – refuser une demande
 *   /friend remove <joueur>  – retirer un ami
 *   /friend list             – liste des amis (connectés / déconnectés)
 *   /friend requests         – demandes reçues en attente
 */
public class FriendCommand extends CoreCommand {

    private static final String PRE = RC.PRE;
    private static final String SEP = RC.SEP;
    private static final String PRE_S = RC.PRE_S;

    private final FriendManager manager;

    public FriendCommand(JavaPlugin plugin, FriendManager manager) {
        super(plugin, "friend", true);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "add":      doAdd(player, args);      break;
            case "accept":   doAccept(player, args);   break;
            case "deny":     doDeny(player, args);     break;
            case "remove":
            case "del":
            case "delete":   doRemove(player, args);   break;
            case "list":     doList(player);            break;
            case "requests":
            case "demandes": doRequests(player);        break;
            default:         sendHelp(player);          break;
        }
    }

    // ── /friend add <joueur> ─────────────────────────────────────────────────

    private void doAdd(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(PRE + "§eUsage §f: /friend add <joueur>"); return; }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { player.sendMessage(PRE + "§cJoueur introuvable ou hors ligne."); return; }
        if (target.equals(player)) { player.sendMessage(PRE + "§cVous ne pouvez pas vous ajouter vous-même."); return; }

        UUID self = player.getUniqueId();
        UUID other = target.getUniqueId();

        if (manager.areFriends(self, other)) {
            player.sendMessage(PRE + "§cVous êtes déjà amis avec §f" + target.getName() + "§c.");
            return;
        }

        if (manager.getFriendCount(self) >= FriendManager.MAX_FRIENDS) {
            player.sendMessage(PRE + "§cVous avez atteint la limite de §f" + FriendManager.MAX_FRIENDS + " §camis.");
            return;
        }

        if (manager.getFriendCount(other) >= FriendManager.MAX_FRIENDS) {
            player.sendMessage(PRE + "§f" + target.getName() + " §ca atteint sa limite d'amis.");
            return;
        }

        // Si la cible a déjà envoyé une demande → accepter automatiquement
        if (manager.hasRequest(other, self)) {
            manager.addFriend(self, player.getName(), other, target.getName());
            player.sendMessage(PRE + "§aVous êtes maintenant amis avec §f" + target.getName() + " §a!");
            target.sendMessage(PRE + "§f" + player.getName() + " §aa accepté votre demande d'ami !");
            return;
        }

        if (manager.hasRequest(self, other)) {
            player.sendMessage(PRE + "§eVous avez déjà envoyé une demande à §f" + target.getName() + "§e.");
            return;
        }

        manager.sendRequest(self, player.getName(), other, target.getName());
        player.sendMessage(PRE + "§aDemande d'ami envoyée à §f" + target.getName() + "§a.");
        target.sendMessage(SEP);
        target.sendMessage(PRE + "§f" + player.getName() + " §evous envoie une demande d'ami !");
        target.sendMessage(PRE_S + "§f/friend accept " + player.getName() + " §7pour accepter");
        target.sendMessage(PRE_S + "§f/friend deny " + player.getName() + "   §7pour refuser");
        target.sendMessage(SEP);
    }

    // ── /friend accept <joueur> ──────────────────────────────────────────────

    private void doAccept(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(PRE + "§eUsage §f: /friend accept <joueur>"); return; }

        UUID self = player.getUniqueId();

        // Chercher la demande : le sender peut être en ligne ou hors ligne
        Map<UUID, String> pending = manager.getPendingRequests(self);
        UUID senderUuid = null;
        String senderName = null;
        for (Map.Entry<UUID, String> entry : pending.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(args[1])) {
                senderUuid = entry.getKey();
                senderName = entry.getValue();
                break;
            }
        }

        if (senderUuid == null) {
            player.sendMessage(PRE + "§cAucune demande de §f" + args[1] + "§c.");
            return;
        }

        if (manager.getFriendCount(self) >= FriendManager.MAX_FRIENDS) {
            player.sendMessage(PRE + "§cVous avez atteint la limite de §f" + FriendManager.MAX_FRIENDS + " §camis.");
            return;
        }

        if (manager.getFriendCount(senderUuid) >= FriendManager.MAX_FRIENDS) {
            player.sendMessage(PRE + "§f" + senderName + " §ca atteint sa limite d'amis.");
            return;
        }

        manager.addFriend(self, player.getName(), senderUuid, senderName);
        player.sendMessage(PRE + "§aVous êtes maintenant amis avec §f" + senderName + " §a!");

        Player senderOnline = Bukkit.getPlayer(senderUuid);
        if (senderOnline != null) {
            senderOnline.sendMessage(PRE + "§f" + player.getName() + " §aa accepté votre demande d'ami !");
        }
    }

    // ── /friend deny <joueur> ────────────────────────────────────────────────

    private void doDeny(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(PRE + "§eUsage §f: /friend deny <joueur>"); return; }

        UUID self = player.getUniqueId();
        Map<UUID, String> pending = manager.getPendingRequests(self);
        UUID senderUuid = null;
        String senderName = null;
        for (Map.Entry<UUID, String> entry : pending.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(args[1])) {
                senderUuid = entry.getKey();
                senderName = entry.getValue();
                break;
            }
        }

        if (senderUuid == null) {
            player.sendMessage(PRE + "§cAucune demande de §f" + args[1] + "§c.");
            return;
        }

        manager.denyRequest(senderUuid, self);
        player.sendMessage(PRE + "§7Demande de §f" + senderName + " §7refusée.");

        Player senderOnline = Bukkit.getPlayer(senderUuid);
        if (senderOnline != null) {
            senderOnline.sendMessage(PRE + "§f" + player.getName() + " §7a refusé votre demande d'ami.");
        }
    }

    // ── /friend remove <joueur> ──────────────────────────────────────────────

    private void doRemove(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(PRE + "§eUsage §f: /friend remove <joueur>"); return; }

        UUID self = player.getUniqueId();
        // Chercher parmi les amis
        List<UUID> friends = manager.getFriends(self);
        UUID targetUuid = null;
        String targetName = null;
        for (UUID fUuid : friends) {
            String name = manager.getName(fUuid);
            if (name != null && name.equalsIgnoreCase(args[1])) {
                targetUuid = fUuid;
                targetName = name;
                break;
            }
        }

        if (targetUuid == null) {
            player.sendMessage(PRE + "§cVous n'êtes pas amis avec §f" + args[1] + "§c.");
            return;
        }

        manager.removeFriend(self, targetUuid);
        player.sendMessage(PRE + "§7§f" + targetName + " §7a été retiré de votre liste d'amis.");

        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null) {
            targetOnline.sendMessage(PRE + "§f" + player.getName() + " §7vous a retiré de sa liste d'amis.");
        }
    }

    // ── /friend list ─────────────────────────────────────────────────────────

    private void doList(Player player) {
        List<UUID> friends = manager.getFriends(player.getUniqueId());

        player.sendMessage(SEP);
        player.sendMessage(PRE + "§e§lVos amis §8(" + friends.size() + "/" + FriendManager.MAX_FRIENDS + ")");

        if (friends.isEmpty()) {
            player.sendMessage(PRE_S + "§7Vous n'avez aucun ami pour le moment.");
            player.sendMessage(PRE_S + "§7Utilisez §f/friend add <joueur> §7pour en ajouter.");
        } else {
            for (UUID fUuid : friends) {
                String name = manager.getName(fUuid);
                if (name == null) name = "§oInconnu";
                boolean online = Bukkit.getPlayer(fUuid) != null;
                String status = online ? "§a● Connecté" : "§8● Déconnecté";
                player.sendMessage(PRE_S + "§f" + name + " §8— " + status);
            }
        }
        player.sendMessage(SEP);
    }

    // ── /friend requests ─────────────────────────────────────────────────────

    private void doRequests(Player player) {
        Map<UUID, String> pending = manager.getPendingRequests(player.getUniqueId());

        player.sendMessage(SEP);
        player.sendMessage(PRE + "§e§lDemandes d'ami reçues §8(" + pending.size() + ")");

        if (pending.isEmpty()) {
            player.sendMessage(PRE_S + "§7Aucune demande en attente.");
        } else {
            for (Map.Entry<UUID, String> entry : pending.entrySet()) {
                player.sendMessage(PRE_S + "§f" + entry.getValue()
                    + " §8— §7/friend accept " + entry.getValue()
                    + " §8| §7/friend deny " + entry.getValue());
            }
        }
        player.sendMessage(SEP);
    }

    // ── Aide ─────────────────────────────────────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage(SEP);
        player.sendMessage(PRE + "§e§lCommandes Amis");
        player.sendMessage(PRE_S + "§f/friend add <joueur>      §8— §7Envoyer une demande d'ami");
        player.sendMessage(PRE_S + "§f/friend accept <joueur>   §8— §7Accepter une demande");
        player.sendMessage(PRE_S + "§f/friend deny <joueur>     §8— §7Refuser une demande");
        player.sendMessage(PRE_S + "§f/friend remove <joueur>   §8— §7Retirer un ami");
        player.sendMessage(PRE_S + "§f/friend list              §8— §7Voir votre liste d'amis");
        player.sendMessage(PRE_S + "§f/friend requests          §8— §7Demandes reçues en attente");
        player.sendMessage(SEP);
    }

    // ── Tab-complétion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player)) return completions;
        Player player = (Player) sender;

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : Arrays.asList("add", "accept", "deny", "remove", "list", "requests")) {
                if (sub.startsWith(prefix)) completions.add(sub);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();

            if (sub.equals("add")) {
                // Joueurs en ligne (sauf soi-même)
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.equals(player) && p.getName().toLowerCase().startsWith(prefix))
                        completions.add(p.getName());
                }
            } else if (sub.equals("accept") || sub.equals("deny")) {
                // Noms des joueurs ayant envoyé une demande
                for (String name : manager.getPendingRequests(player.getUniqueId()).values()) {
                    if (name.toLowerCase().startsWith(prefix)) completions.add(name);
                }
            } else if (sub.equals("remove") || sub.equals("del") || sub.equals("delete")) {
                // Noms des amis
                for (UUID fUuid : manager.getFriends(player.getUniqueId())) {
                    String name = manager.getName(fUuid);
                    if (name != null && name.toLowerCase().startsWith(prefix)) completions.add(name);
                }
            }
        }
        return completions;
    }
}

