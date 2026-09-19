package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /succes} — succès du joueur.
 *
 * <pre>
 * /succes                    ouvre le menu (client moddé) + résumé
 * /succes liste [rubrique]   liste en chat, repli pour un client vanilla
 * /succes info &lt;id&gt;          détail d'un succès
 * /succes recuperer [id|*]   encaisse une récompense, ou toutes
 *
 * /succes reset &lt;joueur&gt;     (staff) efface tout l'avancement
 * /succes debloquer &lt;joueur&gt; &lt;id&gt;  (staff) force un déblocage
 * </pre>
 *
 * <p><b>Le chat n'est pas un repli au rabais.</b> Le menu vit dans le client
 * moddé, mais un joueur doit pouvoir tout consulter et tout encaisser sans lui
 * — c'est ce qui permet de dépanner depuis la console et de ne pas rendre une
 * récompense inatteignable si le client a un souci.
 */
public class SuccesCommand extends CoreCommand {

    static final String PERM_ADMIN = "redconflict.succes.admin";

    private static final List<String> ACTIONS =
            Arrays.asList("liste", "info", "recuperer");
    private static final List<String> ADMIN_ACTIONS =
            Arrays.asList("reset", "debloquer");

    private final SuccesManager manager;
    private final SuccesCatalog catalog;
    private final SuccesPacketSender sender;

    public SuccesCommand(RedConflictCore plugin, SuccesManager manager,
                         SuccesCatalog catalog, SuccesPacketSender sender) {
        super(plugin, "succes", false);
        this.manager = manager;
        this.catalog = catalog;
        this.sender = sender;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            open(sender);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "liste":
            case "list":
                sendList(sender, args.length >= 2 ? args[1] : null);
                break;
            case "info":
                if (args.length < 2) {
                    sender.sendMessage(RC.PRE + "§cUsage : §f/succes info <id>");
                    break;
                }
                sendInfo(sender, args[1]);
                break;
            case "recuperer":
            case "claim":
                claim(sender, args.length >= 2 ? args[1] : "*");
                break;
            case "reset":
                adminReset(sender, args);
                break;
            case "debloquer":
            case "unlock":
                adminUnlock(sender, args);
                break;
            default:
                sendUsage(sender);
        }
    }

    // ── Joueur ───────────────────────────────────────────────────────────────

    private void open(CommandSender commandSender) {
        if (!(commandSender instanceof Player)) {
            sendUsage(commandSender);
            return;
        }
        Player player = (Player) commandSender;

        if (catalog.size() == 0) {
            // Ouvrir une fenêtre vide laisserait croire à une panne du client.
            player.sendMessage(RC.PRE + "§cAucun succès n'est configuré sur ce serveur.");
            player.sendMessage("  §7Un administrateur doit vérifier "
                    + "§fplugins/RedConflictCore/succes/succes.yml §7et le log au démarrage.");
            return;
        }

        int unlocked = manager.unlockedCount(player.getUniqueId());
        int pending = manager.pendingCount(player.getUniqueId());

        // Le client moddé ouvre le menu ; un client vanilla garde ces deux
        // lignes, qui disent l'essentiel et rappellent comment encaisser.
        this.sender.sendInit(player);
        this.sender.sendData(player);
        this.sender.sendOpen(player);

        player.sendMessage(RC.PRE + "Succès : §f" + unlocked + "§7/§f" + catalog.size()
                + (pending > 0 ? " §8— §e" + pending + " §7récompense(s) en attente" : ""));
        if (pending > 0) {
            player.sendMessage("  §7Tout encaisser : §e/succes recuperer *");
        }
    }

    private void sendList(CommandSender commandSender, String categoryFilter) {
        String filter = categoryFilter == null ? null : categoryFilter.toUpperCase(Locale.ROOT);
        Player player = commandSender instanceof Player ? (Player) commandSender : null;

        commandSender.sendMessage(RC.SEP);
        commandSender.sendMessage(RC.PRE + "Succès"
                + (filter == null ? "" : " §8— §7" + filter));

        String current = null;
        int shown = 0;
        for (Succes succes : catalog.all()) {
            if (filter != null && !succes.category.equals(filter)) continue;
            if (!succes.category.equals(current)) {
                current = succes.category;
                commandSender.sendMessage("§8» §f" + current);
            }
            commandSender.sendMessage("  " + line(player, succes));
            shown++;
        }

        if (shown == 0) {
            commandSender.sendMessage("§7Aucun succès dans cette rubrique.");
            commandSender.sendMessage("§7Rubriques : §f" + join(catalog.categories(), "§7, §f"));
        }
        commandSender.sendMessage(RC.SEP);
    }

    /** Une ligne de liste : état, nom, avancement. */
    private String line(Player player, Succes succes) {
        if (player == null) {
            return succes.tierColor() + succes.name + " §8(§7" + succes.goal + "§8) §8· §7" + succes.description;
        }
        SuccesDatabase.Entry entry = manager.entryOf(player.getUniqueId(), succes.id);
        String mark;
        if (!entry.unlocked) mark = "§8✖";
        else if (!entry.claimed) mark = "§e★";
        else mark = "§a✔";

        String progress = entry.unlocked
                ? ""
                : " §8[§7" + Math.min(entry.progress, succes.goal) + "§8/§7" + succes.goal + "§8]";
        String pending = (entry.unlocked && !entry.claimed) ? " §e(à récupérer)" : "";
        return mark + " " + succes.tierColor() + succes.name + progress + pending;
    }

    private void sendInfo(CommandSender commandSender, String id) {
        Succes succes = catalog.get(id);
        if (succes == null) {
            commandSender.sendMessage(RC.PRE + "§cSuccès inconnu : §f" + id);
            return;
        }
        commandSender.sendMessage(RC.SEP);
        commandSender.sendMessage(succes.tierColor() + "§l" + succes.name
                + " §8— §7" + succes.tierColor() + succes.tierName());
        commandSender.sendMessage("§7" + succes.description);
        commandSender.sendMessage("§8» §7Objectif : §f" + succes.goal);
        commandSender.sendMessage("§8» §7Récompense : " + succes.rewardText);
        if (commandSender instanceof Player) {
            SuccesDatabase.Entry entry = manager.entryOf(((Player) commandSender).getUniqueId(), succes.id);
            commandSender.sendMessage("§8» §7Avancement : §f"
                    + Math.min(entry.progress, succes.goal) + "§7/§f" + succes.goal
                    + (entry.unlocked ? (entry.claimed ? " §a(terminé)" : " §e(récompense en attente)") : ""));
        }
        commandSender.sendMessage(RC.SEP);
    }

    private void claim(CommandSender commandSender, String id) {
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage(RC.ERR_PLAYER_ONLY);
            return;
        }
        Player player = (Player) commandSender;

        if ("*".equals(id) || "tout".equalsIgnoreCase(id)) {
            int count = manager.claimAll(player);
            player.sendMessage(count == 0
                    ? RC.PRE + "§7Aucune récompense à récupérer."
                    : RC.PRE + "§a" + count + " §7récompense(s) récupérée(s).");
            return;
        }

        switch (manager.claim(player, id)) {
            case OK:
                break; // le gestionnaire a déjà annoncé le versement
            case LOCKED:
                player.sendMessage(RC.PRE + "§cCe succès n'est pas encore débloqué.");
                break;
            case ALREADY:
                player.sendMessage(RC.PRE + "§cRécompense déjà récupérée.");
                break;
            case INVENTORY_FULL:
                player.sendMessage(RC.PRE + "§cInventaire plein — libérez de la place, la récompense vous attend.");
                break;
            default:
                player.sendMessage(RC.PRE + "§cSuccès inconnu : §f" + id);
        }
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    private void adminReset(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        if (args.length < 2) {
            commandSender.sendMessage(RC.PRE + "§cUsage : §f/succes reset <joueur>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            commandSender.sendMessage(RC.ERR_PLAYER_NOT_FOUND);
            return;
        }
        manager.reset(target.getUniqueId());
        commandSender.sendMessage(RC.PRE + "§7Succès de §f" + target.getName() + " §7remis à zéro.");
    }

    private void adminUnlock(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        if (args.length < 3) {
            commandSender.sendMessage(RC.PRE + "§cUsage : §f/succes debloquer <joueur> <id>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            commandSender.sendMessage(RC.ERR_PLAYER_NOT_FOUND);
            return;
        }
        if (!manager.forceUnlock(target, args[2])) {
            commandSender.sendMessage(RC.PRE + "§cSuccès inconnu : §f" + args[2]);
            return;
        }
        commandSender.sendMessage(RC.PRE + "§7Succès §f" + args[2] + " §7débloqué pour §f" + target.getName() + "§7.");
    }

    private void sendUsage(CommandSender commandSender) {
        commandSender.sendMessage(RC.PRE + "Commande §f/succes");
        commandSender.sendMessage("§8» §f/succes §8- §7Ouvre le menu des succès");
        commandSender.sendMessage("§8» §f/succes liste §8[§7rubrique§8] §8- §7Liste en chat");
        commandSender.sendMessage("§8» §f/succes info §8<§7id§8> §8- §7Détail d'un succès");
        commandSender.sendMessage("§8» §f/succes recuperer §8[§7id§8|§7*§8] §8- §7Encaisse une récompense");
        if (commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage("§8» §f/succes reset §8<§7joueur§8> §8- §7Remet l'avancement à zéro");
            commandSender.sendMessage("§8» §f/succes debloquer §8<§7joueur§8> §8<§7id§8> §8- §7Force un déblocage");
        }
    }

    // ── Complétion ───────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender commandSender, org.bukkit.command.Command command,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            addMatching(out, ACTIONS, args[0]);
            if (commandSender.hasPermission(PERM_ADMIN)) addMatching(out, ADMIN_ACTIONS, args[0]);
            return out;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2) {
            if ("liste".equals(action) || "list".equals(action)) {
                addMatching(out, catalog.categories(), args[1]);
            } else if ("info".equals(action)) {
                addMatching(out, idsOf(), args[1]);
            } else if ("recuperer".equals(action) || "claim".equals(action)) {
                out.add("*");
                addMatching(out, idsOf(), args[1]);
            } else if (("reset".equals(action) || "debloquer".equals(action) || "unlock".equals(action))
                    && commandSender.hasPermission(PERM_ADMIN)) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(online.getName());
                    }
                }
            }
            return out;
        }

        if (args.length == 3 && ("debloquer".equals(action) || "unlock".equals(action))
                && commandSender.hasPermission(PERM_ADMIN)) {
            addMatching(out, idsOf(), args[2]);
        }
        return out;
    }

    private List<String> idsOf() {
        List<String> ids = new ArrayList<>();
        for (Succes succes : catalog.all()) ids.add(succes.id);
        return ids;
    }

    private static void addMatching(List<String> out, List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(candidate);
        }
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(part);
        }
        return sb.toString();
    }
}
