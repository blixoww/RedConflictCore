package fr.originsfight.essentials.command.item;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.Messages;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /clear [joueur] — vide l'inventaire (contenu + armure + curseur).
 * Cibler un autre joueur exige la sous-permission {@code redconflict.clear.others}.
 */
public class ClearCommand extends EssCommand {

    public ClearCommand(CommandEnvironment env) {
        super(env, "clear", false, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.clear")) return false;
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        target.getInventory().clear();
        target.getInventory().setArmorContents(new ItemStack[4]);
        target.setItemOnCursor(null);
        target.updateInventory();

        if (sender == target) {
            target.sendMessage(Text.success("Inventaire vidé."));
        } else {
            sender.sendMessage(Text.success("Inventaire de §f" + target.getName() + " §avidé."));
            target.sendMessage(Text.info("Votre inventaire a été vidé par §f" + sender.getName() + "§7."));
        }
        return true;
    }
}
