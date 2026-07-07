package fr.originsfight.essentials.command.social;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * /help [page] — aide générale, générée depuis le plugin.yml et filtrée par
 * les permissions de l'appelant (une commande dont il n'a pas la permission
 * n'apparaît pas).
 */
public class HelpCommand extends EssCommand {

    public HelpCommand(CommandEnvironment env) {
        super(env, "help", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        int page = 1;
        if (args.length >= 1) {
            Integer parsed = parseInt(sender, args[0]);
            if (parsed == null) return false;
            page = Math.max(1, parsed);
        }

        List<String> lines = buildLines(sender);
        int perPage = Math.max(1, env.getConfig().helpPerPage());
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        page = Math.min(page, pages);

        sender.sendMessage(Text.info("Aide — page §f" + page + "§7/§f" + pages
                + " §7(§f/help <page>§7)"));
        int start = (page - 1) * perPage;
        for (int i = start; i < Math.min(start + perPage, lines.size()); i++) {
            sender.sendMessage(lines.get(i));
        }
        return true;
    }

    /** Une ligne "/commande — description" par commande visible pour l'appelant. */
    private List<String> buildLines(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        Map<String, Map<String, Object>> commands = env.getPlugin().getDescription().getCommands();
        List<String> names = new ArrayList<>(commands.keySet());
        Collections.sort(names);

        for (String name : names) {
            Map<String, Object> spec = commands.get(name);
            Object permission = spec.get("permission");
            if (permission instanceof String && !sender.hasPermission((String) permission)) {
                continue;
            }
            Object description = spec.get("description");
            lines.add("  §8| §f/" + name
                    + (description != null ? " §8- §7" + description : ""));
        }
        return lines;
    }
}
