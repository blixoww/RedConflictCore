package fr.redconflict.essentials.command.item;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.resolve.EnchantmentResolver;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /enchant &lt;enchantement&gt; [niveau] — enchante l'item tenu en main.
 * Dépasser le niveau vanilla ou ignorer la compatibilité item/enchantement
 * exige la sous-permission {@code redconflict.enchant.unsafe}.
 */
public class EnchantCommand extends EssCommand {

    private static final String UNSAFE_PERMISSION = "redconflict.enchant.unsafe";

    private final EnchantmentResolver enchantments;

    public EnchantCommand(CommandEnvironment env, EnchantmentResolver enchantments) {
        super(env, "enchant", true, false);
        this.enchantments = enchantments;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /enchant <enchantement> [niveau]"));
            return false;
        }

        ItemStack hand = player.getItemInHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(Text.error("Vous n'avez rien en main."));
            return false;
        }

        Enchantment enchantment = enchantments.resolve(args[0]);
        if (enchantment == null) {
            player.sendMessage(Text.error("Enchantement inconnu : §f" + args[0]));
            return false;
        }

        int level = 1;
        if (args.length >= 2) {
            Integer parsed = parseInt(player, args[1]);
            if (parsed == null) return false;
            level = parsed;
        }

        // Niveau 0 = retrait de l'enchantement (comportement Essentials).
        if (level <= 0) {
            hand.removeEnchantment(enchantment);
            player.sendMessage(Text.success("Enchantement §f" + args[0].toLowerCase() + " §aretiré."));
            return true;
        }

        boolean unsafe = level > enchantment.getMaxLevel() || !enchantment.canEnchantItem(hand);
        if (unsafe && !player.hasPermission(UNSAFE_PERMISSION)) {
            player.sendMessage(Text.error("Niveau maximum : §f" + enchantment.getMaxLevel()
                    + "§c, et l'enchantement doit être compatible avec l'item."));
            return false;
        }

        hand.addUnsafeEnchantment(enchantment, level);
        player.sendMessage(Text.success("Enchantement §f" + args[0].toLowerCase()
                + " " + level + " §aappliqué."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Enchantment enchantment : Enchantment.values()) {
                String name = enchantment.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix)) matches.add(name);
            }
        }
        return matches;
    }
}
