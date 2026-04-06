package fr.originsfight.lagswitch;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /lagswitch — Commande d'administration du système anti lag-switch.
 *
 * Sous-commandes :
 *   /lagswitch info [joueur]        — Ping, delta, état, incidents
 *   /lagswitch unfreeze <joueur>    — Libère un joueur freezé (auto ou manuel)
 *   /lagswitch freeze <joueur>      — Freeze manuel d'un joueur
 *   /lagswitch reset <joueur>       — Réinitialise incidents + état
 *   /lagswitch status               — Liste des joueurs actuellement restreints
 *   /lagswitch reload               — Recharge config.yml
 *   /lagswitch debug                — Toggle logs debug
 */
public class LagSwitchCommand implements CommandExecutor, TabCompleter {

    private final OriginsFightCore plugin;
    private final LagSwitchManager manager;

    public LagSwitchCommand(OriginsFightCore plugin, LagSwitchManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("redconflict.staff") && !sender.isOp()) {
            sender.sendMessage(RC.ERR_NO_PERM);
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {

            // ── info ──────────────────────────────────────────────────────────
            case "info": {
                Player target = resolveTarget(sender, args, 1);
                if (target == null) return true;
                printInfo(sender, target);
                break;
            }

            // ── unfreeze ──────────────────────────────────────────────────────
            case "unfreeze": {
                if (args.length < 2) {
                    sender.sendMessage(RC.PRE + "§cUsage §f: /lagswitch unfreeze <joueur>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(RC.PRE + "§cJoueur introuvable ou hors-ligne.");
                    return true;
                }
                boolean wasRestricted = manager.isRestricted(target);
                manager.unfreeze(target.getUniqueId());
                if (wasRestricted) {
                    sender.sendMessage(RC.PRE + "§a" + target.getName() + " §alibéré du freeze.");
                    target.sendMessage(RC.PRE + "§aVotre connexion a été vérifiée par un admin — vous êtes libre.");
                } else {
                    sender.sendMessage(RC.PRE + "§e" + target.getName() + " §en'était pas freezé (état réinitialisé quand même).");
                }
                break;
            }

            // ── freeze ────────────────────────────────────────────────────────
            case "freeze": {
                if (args.length < 2) {
                    sender.sendMessage(RC.PRE + "§cUsage §f: /lagswitch freeze <joueur>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(RC.PRE + "§cJoueur introuvable ou hors-ligne.");
                    return true;
                }
                if (target.isOp()) {
                    sender.sendMessage(RC.PRE + "§cImpossible de freezer un OP.");
                    return true;
                }
                manager.manualFreeze(target);
                sender.sendMessage(RC.PRE + "§e" + target.getName() + " §efreezeé manuellement."
                        + " §8(§f/lagswitch unfreeze " + target.getName() + "§8)");
                target.sendMessage("§8[§c§lAntiLag§8] §cVous avez été freezé par un administrateur.");
                break;
            }

            // ── reset ─────────────────────────────────────────────────────────
            case "reset": {
                if (args.length < 2) {
                    sender.sendMessage(RC.PRE + "§cUsage §f: /lagswitch reset <joueur>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(RC.PRE + "§cJoueur introuvable ou hors-ligne.");
                    return true;
                }
                manager.resetPlayer(target.getUniqueId());
                manager.resetIncidents(target.getUniqueId());
                sender.sendMessage(RC.PRE + "§aÉtat + incidents de §f" + target.getName() + " §aréinitialisés.");
                break;
            }

            // ── status ────────────────────────────────────────────────────────
            case "status": {
                sender.sendMessage(RC.SEP);
                sender.sendMessage(RC.PRE + "§eJoueurs actuellement restreints §8:");
                boolean any = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!manager.isRestricted(p)) continue;
                    String statusTag;
                    if (manager.isManuallFrozen(p.getUniqueId())) statusTag = "§d§lFREEZE-ADMIN";
                    else if (manager.isLagging(p))                statusTag = "§c§lLAG";
                    else                                           statusTag = "§e§lGRACE";
                    int ping = manager.getPing(p);
                    int inc  = manager.getIncidents(p.getUniqueId());
                    sender.sendMessage("  §8| §f" + p.getName()
                            + " §8— " + statusTag
                            + " §8| ping=" + ping + "ms"
                            + " §8| incidents=" + inc
                            + " §8| §e/lagswitch unfreeze " + p.getName());
                    any = true;
                }
                if (!any) sender.sendMessage("  §7Aucun joueur restreint.");
                sender.sendMessage(RC.SEP);
                break;
            }

            // ── reload ────────────────────────────────────────────────────────
            case "reload": {
                plugin.reloadConfig();
                manager.disable();
                manager.enable();
                sender.sendMessage(RC.PRE + "§aConfiguration anti lag-switch rechargée.");
                break;
            }

            // ── debug ─────────────────────────────────────────────────────────
            case "debug": {
                boolean newVal = !manager.isDebugMode();
                plugin.getConfig().set("lagswitch.debug", newVal);
                manager.disable();
                manager.enable();
                sender.sendMessage(RC.PRE + "§eMode debug §f: "
                        + (newVal ? "§aactivé §7(logs détaillés en console)" : "§cdésactivé"));
                break;
            }

            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Player resolveTarget(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player t = Bukkit.getPlayerExact(args[idx]);
            if (t == null) { sender.sendMessage(RC.PRE + "§cJoueur introuvable."); return null; }
            return t;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.PRE + "§cPrécisez un joueur.");
            return null;
        }
        return (Player) sender;
    }

    private void printInfo(CommandSender sender, Player target) {
        int  ping    = manager.getPing(target);
        int  pending = manager.getPendingKeepalive(target);
        int  inc     = manager.getIncidents(target.getUniqueId());
        String lag   = manager.isLagging(target)           ? "§c§lOUI" : "§aNon";
        String grace = manager.isInGrace(target)           ? "§e§lOUI" : "§aNon";
        String mFreeze = manager.isManuallFrozen(target.getUniqueId()) ? "§d§lOUI" : "§aNon";
        sender.sendMessage(RC.SEP);
        sender.sendMessage(RC.PRE + "§eAnti Lag-Switch §7— §f" + target.getName());
        sender.sendMessage("  §8| §7Ping             §8: §f" + ping + "ms"
                + "  §8(seuil absolu §c" + manager.getPingThreshold() + "ms"
                + ", delta §c" + manager.getPingDeltaThreshold() + "ms§8)");
        sender.sendMessage("  §8| §7Keepalives att.  §8: §f" + pending);
        sender.sendMessage("  §8| §7En lag-switch    §8: " + lag);
        sender.sendMessage("  §8| §7Grace period     §8: " + grace);
        sender.sendMessage("  §8| §7Freeze admin     §8: " + mFreeze);
        sender.sendMessage("  §8| §7Incidents        §8: §c" + inc);
        if (manager.isRestricted(target)) {
            sender.sendMessage("  §8| §a/lagswitch unfreeze " + target.getName());
        }
        sender.sendMessage(RC.SEP);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(RC.SEP);
        sender.sendMessage(RC.PRE + "§eCommandes §f/lagswitch §8:");
        sender.sendMessage("  §f/lagswitch info [joueur]       §8- §7Ping, état, incidents");
        sender.sendMessage("  §f/lagswitch unfreeze <joueur>   §8- §aLibère un joueur freezé");
        sender.sendMessage("  §f/lagswitch freeze <joueur>     §8- §cFreeze manuel un joueur");
        sender.sendMessage("  §f/lagswitch reset <joueur>      §8- §7Réinitialise état + incidents");
        sender.sendMessage("  §f/lagswitch status              §8- §7Joueurs restreints en ce moment");
        sender.sendMessage("  §f/lagswitch reload              §8- §7Recharge la config");
        sender.sendMessage("  §f/lagswitch debug               §8- §7Toggle logs debug console");
        sender.sendMessage(RC.SEP);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("redconflict.staff") && !sender.isOp()) return new ArrayList<>();

        if (args.length == 1) {
            return Stream.of("info", "unfreeze", "freeze", "reset", "status", "reload", "debug")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("unfreeze") || sub.equals("freeze")
                    || sub.equals("reset")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return new ArrayList<>();
    }
}

