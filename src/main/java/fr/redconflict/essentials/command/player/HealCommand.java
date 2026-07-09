package fr.redconflict.essentials.command.player;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.Messages;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.List;

/**
 * /heal [joueur] — soigne : vie au maximum, faim rechargée, feu éteint,
 * effets négatifs purgés (comportement Essentials).
 */
public class HealCommand extends EssCommand {

    private static final List<PotionEffectType> NEGATIVE_EFFECTS = Arrays.asList(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.WEAKNESS,
            PotionEffectType.SLOW, PotionEffectType.SLOW_DIGGING, PotionEffectType.CONFUSION,
            PotionEffectType.BLINDNESS, PotionEffectType.HUNGER);

    public HealCommand(CommandEnvironment env) {
        super(env, "heal", false, true);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (sender instanceof Player && !checkOthers(sender, "redconflict.heal")) return false;
            target = findOnline(sender, args[0]);
            if (target == null) return false;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.ERR_PLAYER_ONLY);
                return false;
            }
            target = (Player) sender;
        }

        target.setHealth(target.getMaxHealth());
        target.setFoodLevel(20);
        target.setSaturation(10f);
        target.setFireTicks(0);
        for (PotionEffect effect : target.getActivePotionEffects()) {
            if (NEGATIVE_EFFECTS.contains(effect.getType())) {
                target.removePotionEffect(effect.getType());
            }
        }

        if (sender == target) {
            target.sendMessage(Text.success("Vous avez été soigné."));
        } else {
            sender.sendMessage(Text.success("§f" + target.getName() + " §aa été soigné."));
            target.sendMessage(Text.success("Vous avez été soigné."));
        }
        return true;
    }
}
