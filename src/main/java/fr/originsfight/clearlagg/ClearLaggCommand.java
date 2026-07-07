package fr.originsfight.clearlagg;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commande /clearlagg
 *
 * Sous-commandes :
 *   /clearlagg now                — lance immédiatement un clearlagg
 *   /clearlagg info               — affiche la config & le prochain déclenchement
 *   /clearlagg reload             — recharge la config depuis config.yml
 *   /clearlagg count [monde]      — compte les entités actuelles sans les supprimer
 *   /clearlagg help               — aide
 *
 * Permission : redconflict.staff (pour now / reload / count)
 */
public class ClearLaggCommand extends CoreCommand {

    private final OriginsFightCore plugin;
    private final ClearLaggManager manager;

    public ClearLaggCommand(OriginsFightCore plugin, ClearLaggManager manager) {
        super(plugin, "clearlagg", false);
        this.plugin  = plugin;
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase()) {

            // ── now ───────────────────────────────────────────────────────────
            case "now": {
                if (!hasStaffPerm(sender)) return;
                sender.sendMessage("§8[§6§lClearLagg§8] §eLancement immédiat du clearlagg…");
                // Planifier sur le prochain tick pour rester thread-safe
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int removed = manager.runClearLagg();
                    // Le broadcast est déjà fait dans runClearLagg()
                    if (sender instanceof Player) {
                        sender.sendMessage("§8[§6§lClearLagg§8] §f" + removed
                                + " §aentité(s) supprimée(s).");
                    }
                });
                break;
            }

            // ── info ──────────────────────────────────────────────────────────
            case "info": {
                long next = manager.getSecondsUntilNext();
                String nextStr = next < 0 ? "§7N/A" : formatTime(next);

                sender.sendMessage("§8§m          §8[§6§lClearLagg§8]§8§m          ");
                sender.sendMessage("§7Intervalle    §f: §e" + manager.getIntervalMinutes() + " min");
                sender.sendMessage("§7Countdown     §f: §e" + manager.getWarningSeconds() + "s avant");
                sender.sendMessage("§7Prochain      §f: §a" + nextStr);
                sender.sendMessage("§7Items droppés §f: " + bool(manager.isClearItems()));
                sender.sendMessage("§7Flèches       §f: " + bool(manager.isClearArrows()));
                sender.sendMessage("§7Orbes d'XP    §f: " + bool(manager.isClearExpOrbs()));
                sender.sendMessage("§7Tous les mobs §f: " + bool(manager.isClearAllMobs()));
                sender.sendMessage("§7Protège nommés§f: " + bool(manager.isProtectNamed()));
                sender.sendMessage("§7Protège tamed §f: " + bool(manager.isProtectTamed()));
                sender.sendMessage("§7Detect MobStacker §f: " + bool(manager.isDetectMobStacker()));
                sender.sendMessage("§7Force clear named stacked §f: " + bool(manager.isForceClearNamedStacked()));
                if (!manager.getMobStackerKeys().isEmpty())
                    sender.sendMessage("§7MobStacker keys §f: §b" + String.join("§7, §b", manager.getMobStackerKeys()));
                if (!manager.getMobsToClear().isEmpty())
                    sender.sendMessage("§7Mobs supprimés§f: §c"
                            + String.join("§7, §c", manager.getMobsToClear()));
                if (!manager.getExcludedMobs().isEmpty())
                    sender.sendMessage("§7Exclus        §f: §a"
                            + String.join("§7, §a", manager.getExcludedMobs()));
                if (!manager.getExcludedWorlds().isEmpty())
                    sender.sendMessage("§7Mondes exclus §f: §b"
                            + String.join("§7, §b", manager.getExcludedWorlds()));
                sender.sendMessage("§8§m                                  ");
                break;
            }

            // ── reload ────────────────────────────────────────────────────────
            case "reload": {
                if (!hasStaffPerm(sender)) return;
                plugin.reloadConfig();
                manager.reload();
                sender.sendMessage("§8[§6§lClearLagg§8] §aConfiguration rechargée !");
                break;
            }

            // ── count ─────────────────────────────────────────────────────────
            case "count": {
                if (!hasStaffPerm(sender)) return;
                String worldName = args.length > 1 ? args[1] : null;
                List<World> worlds = worldName != null
                        ? Collections.singletonWorld(worldName)
                        : Bukkit.getWorlds();

                if (worldName != null && worlds.isEmpty()) {
                    sender.sendMessage("§cMonde introuvable : §f" + worldName);
                    return;
                }

                sender.sendMessage("§8[§6§lClearLagg§8] §eEntités par monde :");
                int grandTotal = 0;
                for (World w : worlds) {
                    int items = 0, arrows = 0, xp = 0, mobs = 0, other = 0;
                    for (Entity e : w.getEntities()) {
                        EntityType t = e.getType();
                        if (t == EntityType.DROPPED_ITEM)        items++;
                        else if (t == EntityType.ARROW)          arrows++;
                        else if (t == EntityType.EXPERIENCE_ORB) xp++;
                        else if (e instanceof org.bukkit.entity.LivingEntity
                                && !(e instanceof Player))       mobs++;
                        else                                      other++;
                    }
                    int total = items + arrows + xp + mobs + other;
                    grandTotal += total;
                    sender.sendMessage("  §f" + w.getName()
                            + " §8— §fitems=§e" + items
                            + " §fflèches=§e" + arrows
                            + " §fxp=§e" + xp
                            + " §fmobs=§e" + mobs
                            + " §fautres=§e" + other
                            + " §8(total §f" + total + "§8)");
                }
                sender.sendMessage("§8[§6§lClearLagg§8] §fTotal global : §e" + grandTotal);
                break;
            }

            default:
                sendHelp(sender);
        }
    }

    // ── Tab-complétion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("now", "info", "reload", "count", "help");
            List<String> result = new ArrayList<>();
            for (String s : subs)
                if (s.startsWith(args[0].toLowerCase())) result.add(s);
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("count")) {
            List<String> result = new ArrayList<>();
            for (World w : Bukkit.getWorlds())
                if (w.getName().toLowerCase().startsWith(args[1].toLowerCase()))
                    result.add(w.getName());
            return result;
        }
        return new ArrayList<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m          §8[§6§lClearLagg §7aide§8]§8§m          ");
        sender.sendMessage("§e/clearlagg now       §7— Lancer maintenant");
        sender.sendMessage("§e/clearlagg info      §7— Voir la configuration");
        sender.sendMessage("§e/clearlagg reload    §7— Recharger la config");
        sender.sendMessage("§e/clearlagg count [monde] §7— Compter les entités");
        sender.sendMessage("§8§m                                  ");
    }

    private boolean hasStaffPerm(CommandSender sender) {
        if (sender.hasPermission("redconflict.staff") || !(sender instanceof Player)) return true;
        sender.sendMessage("§cVous n'avez pas la permission.");
        return false;
    }

    private String bool(boolean b) {
        return b ? "§aoui" : "§cnon";
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** Mini-helper pour éviter une dépendance externe. */
    private static class Collections {
        static List<World> singletonWorld(String name) {
            World w = Bukkit.getWorld(name);
            List<World> list = new ArrayList<>();
            if (w != null) list.add(w);
            return list;
        }
    }
}
