package fr.redconflict.job;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /metier — Interface de gestion des métiers.
 *
 * Usage joueur :
 *   /metier                 — ouvre le GUI
 *   /metier top             — classement global
 *   /metier top <metier>    — classement par métier
 *
 * Usage staff :
 *   /metier info <joueur>
 *   /metier xp add <joueur> <metier> <montant>
 *   /metier reset <metier> <joueur>
 *
 * NOTE : /metier set supprimé — tous les métiers sont toujours actifs.
 */
public class JobCommand extends CoreCommand {

    private final JobManager       manager;
    private final JobPacketSender  sender;

    private static final List<String> JOB_NAMES = Arrays.asList("MINER", "FARMER", "ARTISAN");
    private static final List<String> TOP_KEYS  = Arrays.asList("ALL", "MINER", "FARMER", "ARTISAN");

    public JobCommand(RedConflictCore plugin, JobManager manager, JobPacketSender sender) {
        super(plugin, "metier", false);
        this.manager = manager;
        this.sender  = sender;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return; }
            Player p = (Player) sender;
            this.sender.sendJobInit(p);
            this.sender.sendJobData(p, manager.getData(p.getUniqueId()));
            this.sender.sendJobOpen(p);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // ── /metier topupdate ─ (staff) recalcule immédiatement le snapshot ─────
        if (sub.equals("topupdate") && sender.hasPermission("jobs.admin")) {
            sender.sendMessage("§e[Métier] Recalcul du classement en cours...");
            manager.getTopManager().refreshAsync(() -> {
                int n = manager.getTopManager().getSnapshot("ALL").size();
                sender.sendMessage("§a[Métier] Classement mis à jour §7(" + n + " joueur(s) classé(s)).");
            });
            return;
        }

        // ── /metier top [metier] ─ lit le snapshot figé (recalculé toutes les 24h) ─
        if (sub.equals("top")) {
            String jk = args.length >= 2 ? args[1].toUpperCase(Locale.ROOT) : "ALL";
            List<JobDatabase.TopEntry> entries = manager.getTopManager().getSnapshot(jk);

            // Retour chat (toujours visible, même GUI fermé).
            sender.sendMessage(RC.PRE + "Classement métier §7(§e" + jk + "§7)");
            if (entries.isEmpty()) {
                sender.sendMessage("§7Aucun joueur classé pour le moment.");
            } else {
                int rank = 1;
                for (JobDatabase.TopEntry e : entries) {
                    sender.sendMessage("§e#" + (rank++) + " §f" + e.name
                            + " §7— niv.§f" + e.level + " §7(§f" + e.xp + " §7XP)");
                }
            }
            // Met aussi à jour l'onglet classement du GUI s'il est ouvert.
            if (sender instanceof Player) this.sender.sendTop((Player) sender, jk, entries);
            return;
        }

        // ── /metier info <joueur> ─────────────────────────────────────────────
        if (sub.equals("info") && sender.hasPermission("jobs.admin")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /metier info <joueur>"); return; }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) { sender.sendMessage(RC.ERR_PLAYER_NOT_FOUND); return; }
            JobDatabase.JobData d = manager.getData(target.getUniqueId());
            sender.sendMessage("§6[Métier] §e" + target.getName() + " :");
            sender.sendMessage("  §7Mineur : §fniv." + d.minerLevel + " §7(" + d.minerXp + " XP)");
            sender.sendMessage("  §7Agriculteur : §fniv." + d.farmerLevel + " §7(" + d.farmerXp + " XP)");
            sender.sendMessage("  §7Artisan : §fniv." + d.artisanLevel + " §7(" + d.artisanXp + " XP)");
            return;
        }

        // ── /metier xp add <joueur> <metier> <montant> ───────────────────────
        if (sub.equals("xp") && sender.hasPermission("jobs.admin")) {
            if (args.length < 5 || !args[1].equalsIgnoreCase("add")) {
                sender.sendMessage("§cUsage: /metier xp add <joueur> <metier> <montant>"); return;
            }
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) { sender.sendMessage(RC.ERR_PLAYER_NOT_FOUND); return; }
            JobType jt = JobType.fromString(args[3]);
            if (!jt.isReal()) { sender.sendMessage("§cMétier inconnu. Valides : MINER, FARMER, ARTISAN"); return; }
            int amount;
            try { amount = Integer.parseInt(args[4]); } catch (NumberFormatException e) {
                sender.sendMessage("§cMontant invalide."); return;
            }
            manager.addXpAdmin(target, jt, amount);
            sender.sendMessage("§a[Métier] §f+" + amount + " XP §a(" + jt.displayName + ") donné à §f" + target.getName());
            return;
        }

        // ── /metier reset <metier> <joueur> ──────────────────────────────────
        if (sub.equals("reset") && sender.hasPermission("jobs.admin")) {
            if (args.length < 3) { sender.sendMessage("§cUsage: /metier reset <metier> <joueur>"); return; }
            JobType jt = JobType.fromString(args[1]);
            if (!jt.isReal()) { sender.sendMessage("§cMétier inconnu."); return; }
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) { sender.sendMessage(RC.ERR_PLAYER_NOT_FOUND); return; }
            manager.resetJob(target.getUniqueId(), jt);
            sender.sendMessage("§a[Métier] Progression §f" + jt.displayName + " §ade §f" + target.getName() + " §aréinitialisée.");
            return;
        }

        sender.sendMessage("§cSous-commande inconnue. §e/metier [top|info|xp|reset]");
        return;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("top"));
            if (sender.hasPermission("jobs.admin")) opts.addAll(Arrays.asList("topupdate", "info", "xp", "reset"));
            return opts.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String s = args[0].toLowerCase();
            if (s.equals("top")) return TOP_KEYS.stream().filter(k -> k.startsWith(args[1].toUpperCase())).collect(Collectors.toList());
            if (s.equals("info") || s.equals("reset"))
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            if (s.equals("xp")) return Collections.singletonList("add");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("xp"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        if (args.length == 4 && args[0].equalsIgnoreCase("xp"))
            return JOB_NAMES.stream().filter(s -> s.startsWith(args[3].toUpperCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("reset"))
            return JOB_NAMES.stream().filter(s -> s.startsWith(args[2].toUpperCase())).collect(Collectors.toList());
        return Collections.emptyList();
    }
}
