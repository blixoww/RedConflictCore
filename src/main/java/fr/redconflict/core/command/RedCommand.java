package fr.redconflict.core.command;

import fr.redconflict.core.Module;
import fr.redconflict.core.ModuleManager;
import fr.redconflict.core.text.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /red — administration du plugin : rechargement à chaud des configurations,
 * état des modules, import des données EssentialsX.
 *
 * <p>L'action d'import est injectée au câblage (interface fonctionnelle) pour
 * ne pas coupler le socle core au module essentials.
 */
public class RedCommand implements CommandExecutor, TabCompleter {

    /** Action d'import différée (sender, force). */
    public interface ImportAction {
        void run(CommandSender sender, boolean force);
    }

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final ImportAction essentialsImport;

    public RedCommand(JavaPlugin plugin, ModuleManager modules, ImportAction essentialsImport) {
        this.plugin = plugin;
        this.modules = modules;
        this.essentialsImport = essentialsImport;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                int reloaded = modules.reloadAll();
                sender.sendMessage(Text.success("Configuration rechargée §7(" + reloaded + " module(s) rechargé(s))."));
                return true;

            case "modules": {
                List<Module> enabled = modules.getEnabledModules();
                StringBuilder names = new StringBuilder();
                for (Module module : enabled) {
                    if (names.length() > 0) names.append("§7, §f");
                    names.append(module.getName());
                }
                sender.sendMessage(Text.info("Modules actifs (§f" + enabled.size() + "§7) : §f" + names));
                if (!modules.getFailedModules().isEmpty()) {
                    sender.sendMessage(Text.error("Modules en échec : §f"
                            + String.join("§7, §f", modules.getFailedModules())));
                }
                return true;
            }

            case "import":
                if (args.length < 2 || !args[1].equalsIgnoreCase("essentials")) {
                    sender.sendMessage(Text.error("Usage : /red import essentials [force]"));
                    return true;
                }
                if (essentialsImport == null) {
                    sender.sendMessage(Text.error("Import indisponible (module essentials inactif)."));
                    return true;
                }
                boolean force = args.length >= 3 && args[2].equalsIgnoreCase("force");
                essentialsImport.run(sender, force);
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.info("Commande §f/red §7:"));
        sender.sendMessage("  §8| §f/red reload §8- §7Recharge les configurations à chaud.");
        sender.sendMessage("  §8| §f/red modules §8- §7Liste l'état des modules.");
        sender.sendMessage("  §8| §f/red import essentials [force] §8- §7Importe soldes/homes/seen d'EssentialsX.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("reload", "modules", "import"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return filter(Arrays.asList("essentials"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("import")) {
            return filter(Arrays.asList("force"), args[2]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> matches = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String option : options) {
            if (option.startsWith(lower)) matches.add(option);
        }
        return matches;
    }
}
