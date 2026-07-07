package fr.originsfight.essentials.command.item;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /craft (alias /wb) — ouvre une table de craft virtuelle.
 */
public class WorkbenchCommand extends EssCommand {

    public WorkbenchCommand(CommandEnvironment env) {
        super(env, "craft", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        player.openWorkbench(null, true);
        return true;
    }
}
