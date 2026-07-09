package fr.redconflict.essentials.command.social;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /list — joueurs connectés (les joueurs vanish restent masqués pour
 * ceux qui ne peuvent pas les voir).
 */
public class ListCommand extends EssCommand {

    public ListCommand(CommandEnvironment env) {
        super(env, "list", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player viewer = sender instanceof Player ? (Player) sender : null;
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.canSee(online)) {
                names.add(online.getName());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        sender.sendMessage(Text.info("Joueurs en ligne (§f" + names.size() + "§7/§f"
                + Bukkit.getMaxPlayers() + "§7) :"));
        sender.sendMessage("  §f" + (names.isEmpty() ? "§7Personne..." : String.join("§7, §f", names)));
        return true;
    }
}
