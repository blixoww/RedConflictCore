package fr.originsfight.essentials.command.item;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.resolve.ItemResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /give &lt;joueur&gt; &lt;item&gt; [quantité] — donne un item (admin).
 * Accepte les noms Bukkit et les ids 1.8 ({@code 35:14}) ; sans quantité,
 * donne un stack complet. Le surplus est déposé au sol comme Essentials.
 */
public class GiveCommand extends EssCommand {

    private final ItemResolver items;

    public GiveCommand(CommandEnvironment env, ItemResolver items) {
        super(env, "give", false, false);
        this.items = items;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.error("Usage : /give <joueur> <item> [quantité]"));
            return false;
        }
        Player target = findOnline(sender, args[0]);
        if (target == null) return false;

        ItemStack item = items.resolve(args[1]);
        if (item == null) {
            sender.sendMessage(Text.error("Item inconnu : §f" + args[1]));
            return false;
        }

        int amount = item.getType().getMaxStackSize();
        if (args.length >= 3) {
            Integer parsed = parseInt(sender, args[2]);
            if (parsed == null) return false;
            if (parsed <= 0) {
                sender.sendMessage(Text.error("La quantité doit être positive."));
                return false;
            }
            amount = Math.min(parsed, 2304); // 36 stacks max, garde-fou anti-typo
        }
        item.setAmount(amount);

        for (ItemStack rest : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }

        String description = "§f" + amount + "x " + item.getType().name().toLowerCase();
        sender.sendMessage(Text.success("Donné " + description + " §aà §f" + target.getName() + "§a."));
        if (sender != target) {
            target.sendMessage(Text.info("Vous avez reçu " + description + "§7."));
        }
        return true;
    }
}
