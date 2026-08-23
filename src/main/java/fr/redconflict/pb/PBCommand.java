package fr.redconflict.pb;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import fr.redconflict.core.command.CoreCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /pb [joueur]               → solde
 * /pb add <joueur> <n>       → ajoute (staff)
 * /pb remove <joueur> <n>    → retire  (staff)
 * /pb set <joueur> <n>       → fixe   (staff)
 * Permission staff : redconflict.pb.admin
 */
public class PBCommand extends CoreCommand {

    private static final String PERM_ADMIN = "redconflict.pb.admin";
    private static final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cPB&8] &r");

    private final PBManager manager;

    public PBCommand(JavaPlugin plugin, PBManager manager) {
        super(plugin, "pb", false);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Usage : /pb <joueur>");
                return;
            }
            showBalance(sender, (Player) sender);
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("migrate-site")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Permission refusée.");
                return;
            }
            migrateToSite(sender, args);
            return;
        }

        if (sub.equals("add") || sub.equals("remove") || sub.equals("set")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Permission refusée.");
                return;
            }
            if (args.length < 3) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Usage : /pb " + sub + " <joueur> <montant>");
                return;
            }
            OfflinePlayer target = resolve(args[1]);
            if (target == null) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Joueur introuvable.");
                return;
            }
            int amount;
            try { amount = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Montant invalide.");
                return;
            }
            if (amount < 0) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Le montant doit être positif.");
                return;
            }

            String reason = "CMD:" + sender.getName();
            switch (sub) {
                case "add":
                    manager.add(target, amount, reason);
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "+" + amount + " PB → "
                            + ChatColor.WHITE + target.getName()
                            + ChatColor.GRAY + " (solde " + manager.get(target) + ")");
                    notifyTarget(target, "&a+" + amount + " PB");
                    break;
                case "remove":
                    if (manager.remove(target, amount, reason)) {
                        sender.sendMessage(PREFIX + ChatColor.YELLOW + "-" + amount + " PB → "
                                + ChatColor.WHITE + target.getName()
                                + ChatColor.GRAY + " (solde " + manager.get(target) + ")");
                        notifyTarget(target, "&c-" + amount + " PB");
                    } else {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Solde insuffisant ("
                                + manager.get(target) + " PB).");
                    }
                    break;
                case "set":
                    manager.set(target, amount, reason);
                    sender.sendMessage(PREFIX + ChatColor.AQUA + "Solde de " + target.getName()
                            + " défini à " + amount + " PB.");
                    notifyTarget(target, "&7Ton solde PB a été défini à &e" + amount);
                    break;
            }
            return;
        }

        OfflinePlayer target = resolve(args[0]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Joueur introuvable.");
            return;
        }
        showBalance(sender, target);
    }

    private void showBalance(CommandSender to, OfflinePlayer of) {
        to.sendMessage(PREFIX + ChatColor.GRAY + "Solde de " + ChatColor.WHITE + of.getName()
                + ChatColor.GRAY + " : " + ChatColor.YELLOW + manager.get(of) + " PB");
    }

    /**
     * {@code /pb migrate-site [confirm]} — transfère les PB de H2 vers la bourse
     * d'Azuriom, une fois pour toutes.
     *
     * <p>Sans {@code confirm}, ne fait qu'annoncer ce qui serait transféré : une
     * commande qui touche au solde de tout le monde ne s'exécute pas sur une
     * faute de frappe. L'opération est de toute façon rejouable — chaque joueur
     * migré est marqué dans {@code rc_pb_log}.
     */
    private void migrateToSite(CommandSender sender, String[] args) {
        if (!(plugin instanceof fr.redconflict.RedConflictCore)) return;
        final fr.redconflict.RedConflictCore core = (fr.redconflict.RedConflictCore) plugin;

        if (core.getSiteDatabase() == null || !core.getSiteDatabase().isAvailable()) {
            sender.sendMessage(PREFIX + ChatColor.RED
                    + "Le pont vers le site est fermé (site.enabled ?). Rien n'a été fait.");
            return;
        }

        final boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
        sender.sendMessage(PREFIX + ChatColor.GRAY
                + (confirm ? "Transfert des PB vers la bourse du site…" : "Simulation en cours…"));

        final fr.redconflict.site.PBMigration migration =
                new fr.redconflict.site.PBMigration(core, core.getCoreDatabase(), core.getSiteDatabase());

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = migration.run(!confirm).toString();
                } catch (RuntimeException e) {
                    result = ChatColor.RED + "Interrompu : " + e.getMessage();
                }
                final String message = result;
                org.bukkit.Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(PREFIX + ChatColor.WHITE + message);
                        if (!confirm) {
                            sender.sendMessage(PREFIX + ChatColor.YELLOW
                                    + "Rien n'a été écrit. Relance avec « /pb migrate-site confirm ».");
                        }
                    }
                });
            }
        });
    }

    private void notifyTarget(OfflinePlayer of, String msgColored) {
        if (of.isOnline()) {
            Player p = of.getPlayer();
            if (p != null) p.sendMessage(PREFIX + ChatColor.translateAlternateColorCodes('&', msgColored));
        }
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>();
            if (sender.hasPermission(PERM_ADMIN)) opts.addAll(Arrays.asList("add", "remove", "set"));
            for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
            return filter(opts, args[0]);
        }
        if (args.length == 2 && Arrays.asList("add", "remove", "set").contains(args[0].toLowerCase())) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String prefix) {
        List<String> out = new ArrayList<>();
        String pl = prefix.toLowerCase();
        for (String s : src) if (s.toLowerCase().startsWith(pl)) out.add(s);
        return out;
    }
}
