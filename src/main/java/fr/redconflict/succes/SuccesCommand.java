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
import java.util.UUID;

/**
 * {@code /succes} — succès du joueur.
 *
 * <pre>
 * /succes                    ouvre le menu (client moddé) + résumé
 * /succes &lt;joueur&gt;           la fiche d'un AUTRE joueur, en lecture seule
 * /succes liste [rubrique]   liste en chat, repli pour un client vanilla
 * /succes info &lt;id&gt;          détail d'un succès
 * /succes recuperer [id|*]   encaisse une récompense, ou toutes
 *
 * /succes reset &lt;joueur&gt; &lt;all|id&gt; [confirm]  (staff) efface l'avancement
 * /succes debloquer &lt;joueur&gt; &lt;id&gt;            (staff) force un déblocage
 * </pre>
 *
 * <p><b>Un pseudo en premier argument n'est pas une sous-commande.</b> Tout ce
 * qui n'est pas un verbe connu est traité comme un nom de joueur — la même
 * convention que {@code /profil}, pour que consulter quelqu'un reste un geste
 * d'une seule frappe. Un joueur qui s'appellerait « liste » ou « reset » ne
 * serait pas consultable ainsi ; aucun pseudo Minecraft ne ressemble à ça, et
 * l'inverse (préfixer par un verbe) coûterait une frappe à tout le monde.
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
            Arrays.asList("reset", "debloquer", "debug");

    /** Remise à zéro totale ; sinon l'argument est un identifiant de succès. */
    private static final List<String> RESET_ALL = Arrays.asList("all", "tout", "*");

    private final RedConflictCore core;
    private final SuccesManager manager;
    private final SuccesCatalog catalog;
    private final SuccesPacketSender sender;

    public SuccesCommand(RedConflictCore plugin, SuccesManager manager,
                         SuccesCatalog catalog, SuccesPacketSender sender) {
        super(plugin, "succes", false);
        this.core = plugin;
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
            case "debug":
                adminDebug(sender, args);
                break;
            default:
                // Ni verbe ni alias : c'est un pseudo. Voir l'en-tête de classe.
                openOther(sender, args[0]);
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

    /**
     * {@code /succes <joueur>} — la fiche de quelqu'un d'autre, en lecture seule.
     *
     * <p>Ouverte à tous, sans permission : un succès est un fait public, et se
     * comparer fait partie du jeu. Le client ouvre le même écran que pour soi,
     * sans les boutons — on ne réclame pas la récompense d'autrui.
     *
     * <p>Fonctionne sur un joueur absent : l'avancement est relu en base. C'est
     * la seule branche asynchrone de la commande, et elle l'est pour cette
     * raison-là uniquement.
     */
    private void openOther(CommandSender commandSender, String name) {
        if (!(commandSender instanceof Player)) {
            // Depuis la console, la fiche n'a nulle part à s'ouvrir : /succes
            // liste rend le même service en texte.
            sendUsage(commandSender);
            return;
        }
        final Player viewer = (Player) commandSender;

        if (catalog.size() == 0) {
            viewer.sendMessage(RC.PRE + "§cAucun succès n'est configuré sur ce serveur.");
            return;
        }

        final UUID uuid = manager.resolvePlayer(name);
        if (uuid == null) {
            viewer.sendMessage(RC.PRE + "§cJoueur inconnu du serveur : §f" + name);
            viewer.sendMessage("  §7Sans argument, §f/succes §7ouvre le tien.");
            return;
        }
        if (uuid.equals(viewer.getUniqueId())) {
            open(viewer);
            return;
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            // Connecté : son avancement est en mémoire, rien à attendre.
            show(viewer, online.getName(), manager.snapshotOf(uuid));
            return;
        }

        final String typed = name;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final java.util.Map<String, SuccesDatabase.Entry> snapshot = manager.snapshotOf(uuid);
                final String display = resolveName(uuid, typed);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        // Le demandeur a pu partir pendant la lecture.
                        if (viewer.isOnline()) show(viewer, display, snapshot);
                    }
                });
            }
        });
    }

    /**
     * Envoie la fiche au client moddé, et le résumé en chat qui la remplace sur
     * un client vanilla.
     */
    private void show(Player viewer, String display,
                      java.util.Map<String, SuccesDatabase.Entry> snapshot) {
        // Le catalogue d'abord : la fiche ne porte que l'avancement, et un client
        // qui ne l'aurait pas encore recu (connexion juste avant l'envoi initial)
        // afficherait une fenetre vide — qu'on lirait comme « ce joueur n'a rien
        // fait » plutot que comme « il manque une donnee ».
        this.sender.sendInit(viewer);
        this.sender.sendView(viewer, display, snapshot);

        int unlocked = 0;
        int pending = 0;
        for (Succes succes : catalog.all()) {
            SuccesDatabase.Entry entry = snapshot.get(succes.id);
            if (entry == null || !entry.unlocked) continue;
            unlocked++;
            if (!entry.claimed) pending++;
        }

        viewer.sendMessage(RC.PRE + "Succès de §f" + display + " §7: §f"
                + unlocked + "§7/§f" + catalog.size()
                + (pending > 0 ? " §8— §e" + pending + " §7non réclamé(s)" : ""));
    }

    /**
     * Le pseudo tel que la base l'écrit, à défaut celui qui a été tapé : afficher
     * « leziink » en titre quand le compte s'appelle « Leziink » donne
     * l'impression de regarder quelqu'un d'autre.
     *
     * <p>Lecture H2 : à n'appeler que depuis un thread asynchrone.
     */
    private String resolveName(UUID uuid, String fallback) {
        fr.redconflict.data.PlayerDatabase profiles = core.getPlayerDatabase();
        if (profiles != null) {
            fr.redconflict.data.PlayerDatabase.PlayerProfile profile = profiles.getProfile(uuid);
            if (profile != null && profile.name != null && !profile.name.isEmpty()) {
                return profile.name;
            }
        }
        return fallback;
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

    /**
     * {@code /succes reset <joueur> <all|id> [confirm]} — efface l'avancement.
     *
     * <p><b>La portée est obligatoire</b>, et c'est volontaire : {@code all}
     * efface tout, un identifiant n'efface que ce succès-là. Un
     * {@code /succes reset <joueur>} qui aurait tout emporté par défaut serait
     * un piège — la frappe la plus courte serait la plus destructrice.
     *
     * <p>Rouvrir un seul succès est le geste utile au quotidien : un objectif
     * dont le barème change, un déblocage accordé par erreur. Tout remettre à
     * zéro reste possible, mais devient un choix explicite.
     *
     * <p>Sans {@code confirm}, la commande ne fait qu'annoncer ce qu'elle
     * détruirait. Il n'y a pas d'annulation : le seul moment où l'on peut encore
     * changer d'avis, c'est avant. Le geste ne demande rien quand il n'y a rien
     * à perdre.
     */
    private void adminReset(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        if (args.length < 3) {
            commandSender.sendMessage(RC.PRE + "§cUsage : §f/succes reset <joueur> <all|id> [confirm]");
            commandSender.sendMessage("  §7§fall §8- §7tout l'avancement  §8·  §f<id> §8- §7un seul succès");
            return;
        }

        String name = args[1];
        UUID uuid = manager.resolvePlayer(name);
        if (uuid == null) {
            commandSender.sendMessage(RC.PRE + "§cJoueur inconnu du serveur : §f" + name);
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) name = online.getName();

        String scope = args[2];
        boolean all = RESET_ALL.contains(scope.toLowerCase(Locale.ROOT));
        Succes succes = all ? null : catalog.get(scope);
        if (!all && succes == null) {
            commandSender.sendMessage(RC.PRE + "§cSuccès inconnu : §f" + scope);
            commandSender.sendMessage("  §7Les identifiants sont dans §f/succes liste§7 ; "
                    + "§fall §7efface tout.");
            return;
        }

        SuccesManager.ResetPreview preview = all
                ? manager.previewReset(uuid)
                : manager.previewReset(uuid, succes.id);
        boolean confirmed = args.length >= 4 && "confirm".equalsIgnoreCase(args[3]);
        String what = all ? "tous les succès" : "le succès §f" + succes.name;

        if (!confirmed && !preview.isEmpty()) {
            commandSender.sendMessage(RC.PRE + "Remise à zéro de §7" + what + " §7de §f" + name
                    + (online == null ? " §8(hors ligne)" : "") + " §7:");
            commandSender.sendMessage("  §8» §f" + preview.unlocked + " §7débloqué(s), dont §e"
                    + preview.pending + " §7récompense(s) non réclamée(s)");
            commandSender.sendMessage("  §8» §f" + preview.started + " §7en cours d'avancement");
            commandSender.sendMessage("  §cIrréversible§7. Confirmez : §f/succes reset "
                    + name + " " + scope + " confirm");
            return;
        }

        if (all) {
            manager.reset(uuid);
        } else {
            manager.resetSucces(uuid, succes.id);
        }
        commandSender.sendMessage(RC.PRE + "§7Remis à zéro : " + what + " §7de §f" + name
                + (online == null ? " §8(hors ligne)" : "") + "§7.");
        // Un effacement se trace : c'est le seul moyen de savoir qui l'a demandé.
        plugin.getLogger().info("[Succes] " + commandSender.getName()
                + " a remis à zéro " + (all ? "tous les succès" : "le succès " + succes.id)
                + " de " + name + " (" + preview.unlocked
                + " débloqué(s), " + preview.started + " en cours).");
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

    /**
     * {@code /succes debug [joueur]} — les derniers faits reçus par le système.
     *
     * <p>Répond à la seule question utile quand un succès n'avance pas :
     * l'événement est-il arrivé jusqu'ici ? Une ligne par fait, avec le nombre
     * de succès qu'il concernait.
     */
    private void adminDebug(CommandSender commandSender, String[] args) {
        if (!commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage(RC.ERR_NO_PERM);
            return;
        }

        String name = (args.length >= 2) ? args[1]
                : (commandSender instanceof Player ? commandSender.getName() : null);
        if (name == null) {
            commandSender.sendMessage(RC.PRE + "§cUsage : §f/succes debug <joueur>");
            return;
        }

        UUID uuid = manager.resolvePlayer(name);
        if (uuid == null) {
            commandSender.sendMessage(RC.PRE + "§cJoueur inconnu du serveur : §f" + name);
            return;
        }

        List<String> lines = manager.traceOf(uuid);
        commandSender.sendMessage(RC.SEP);
        commandSender.sendMessage(RC.PRE + "Derniers faits reçus — §f" + name);
        if (lines.isEmpty()) {
            commandSender.sendMessage("§7Aucun. Le système n'a reçu aucun événement pour ce joueur");
            commandSender.sendMessage("§7depuis son arrivée §8(§7ou il est hors ligne§8)§7.");
        } else {
            for (String line : lines) {
                commandSender.sendMessage("  §8» " + line);
            }
        }
        commandSender.sendMessage(RC.SEP);
    }

    private void sendUsage(CommandSender commandSender) {
        commandSender.sendMessage(RC.PRE + "Commande §f/succes");
        commandSender.sendMessage("§8» §f/succes §8- §7Ouvre le menu des succès");
        commandSender.sendMessage("§8» §f/succes §8<§7joueur§8> §8- §7Les succès d'un autre joueur");
        commandSender.sendMessage("§8» §f/succes liste §8[§7rubrique§8] §8- §7Liste en chat");
        commandSender.sendMessage("§8» §f/succes info §8<§7id§8> §8- §7Détail d'un succès");
        commandSender.sendMessage("§8» §f/succes recuperer §8[§7id§8|§7*§8] §8- §7Encaisse une récompense");
        if (commandSender.hasPermission(PERM_ADMIN)) {
            commandSender.sendMessage("§8» §f/succes reset §8<§7joueur§8> §8<§7all§8|§7id§8> §8[§7confirm§8] §8- §7Remet à zéro");
            commandSender.sendMessage("§8» §f/succes debloquer §8<§7joueur§8> §8<§7id§8> §8- §7Force un déblocage");
            commandSender.sendMessage("§8» §f/succes debug §8[§7joueur§8] §8- §7Derniers faits reçus");
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
            // Les pseudos aussi : /succes <joueur> est la forme la plus courante
            // après /succes tout court.
            addOnline(out, args[0]);
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
            } else if (("reset".equals(action) || "debloquer".equals(action)
                    || "unlock".equals(action) || "debug".equals(action))
                    && commandSender.hasPermission(PERM_ADMIN)) {
                addOnline(out, args[1]);
            }
            return out;
        }

        if (args.length == 3 && commandSender.hasPermission(PERM_ADMIN)) {
            if ("debloquer".equals(action) || "unlock".equals(action)) {
                addMatching(out, idsOf(), args[2]);
            } else if ("reset".equals(action)) {
                // « all » en tête : c'est la portée qu'on cherche le plus souvent,
                // et la liste des identifiants est longue.
                addMatching(out, java.util.Collections.singletonList("all"), args[2]);
                addMatching(out, idsOf(), args[2]);
            }
            return out;
        }

        if (args.length == 4 && "reset".equals(action) && commandSender.hasPermission(PERM_ADMIN)) {
            addMatching(out, java.util.Collections.singletonList("confirm"), args[3]);
        }
        return out;
    }

    /** Pseudos connectés commençant par ce préfixe. */
    private static void addOnline(List<String> out, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) out.add(online.getName());
        }
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
