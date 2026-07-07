package fr.originsfight.essentials.command.item;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /more — complète le stack tenu en main jusqu'à sa taille maximale.
 */
public class MoreCommand extends EssCommand {

    public MoreCommand(CommandEnvironment env) {
        super(env, "more", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        ItemStack hand = player.getItemInHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(Text.error("Vous n'avez rien en main."));
            return false;
        }
        int max = hand.getType().getMaxStackSize();
        if (hand.getAmount() >= max) {
            player.sendMessage(Text.info("Ce stack est déjà complet (§f" + max + "§7)."));
            return false;
        }
        hand.setAmount(max);
        player.setItemInHand(hand);
        player.sendMessage(Text.success("Stack complété à §f" + max + "§a."));
        return true;
    }
}
