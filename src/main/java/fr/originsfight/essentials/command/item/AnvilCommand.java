package fr.originsfight.essentials.command.item;

import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

/**
 * /anvil — ouvre une enclume virtuelle (sans bloc posé).
 */
public class AnvilCommand extends EssCommand {

    public AnvilCommand(CommandEnvironment env) {
        super(env, "anvil", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        player.openInventory(Bukkit.createInventory(player, InventoryType.ANVIL));
        return true;
    }
}
