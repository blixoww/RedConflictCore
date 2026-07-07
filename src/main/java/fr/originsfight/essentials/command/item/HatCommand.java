package fr.originsfight.essentials.command.item;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /hat — place l'item tenu en main sur la tête (cosmétique).
 * L'ancien casque revient dans la main.
 */
public class HatCommand extends EssCommand {

    public HatCommand(CommandEnvironment env) {
        super(env, "hat", true, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        ItemStack hand = player.getItemInHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(Text.error("Vous n'avez rien en main."));
            return false;
        }

        ItemStack helmet = player.getInventory().getHelmet();
        // Seule une unité part sur la tête ; le reste du stack reste en main.
        ItemStack hat = hand.clone();
        hat.setAmount(1);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
            player.setItemInHand(hand);
            if (helmet != null && helmet.getType() != Material.AIR) {
                player.getInventory().addItem(helmet);
            }
        } else {
            player.setItemInHand(helmet != null && helmet.getType() != Material.AIR ? helmet : null);
        }
        player.getInventory().setHelmet(hat);
        player.updateInventory();
        player.sendMessage(Text.success("Nouveau couvre-chef équipé !"));
        return true;
    }
}
