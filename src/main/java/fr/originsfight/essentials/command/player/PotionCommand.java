package fr.originsfight.essentials.command.player;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.command.CommandEnvironment;
import fr.originsfight.essentials.command.EssCommand;
import fr.originsfight.essentials.service.resolve.PotionResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /potion &lt;type&gt; &lt;durée&gt; &lt;niveau&gt; — s'applique un effet de potion
 * (durée en secondes, niveau 1 = amplificateur 0). {@code /potion clear}
 * retire tous les effets actifs.
 */
public class PotionCommand extends EssCommand {

    private final PotionResolver potions;

    public PotionCommand(CommandEnvironment env, PotionResolver potions) {
        super(env, "potion", true, false);
        this.potions = potions;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;

        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            player.sendMessage(Text.success("Tous vos effets ont été retirés."));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(Text.error("Usage : /potion <type> <durée en s> <niveau> §7ou §f/potion clear"));
            return false;
        }

        PotionEffectType type = potions.resolve(args[0]);
        if (type == null) {
            player.sendMessage(Text.error("Effet inconnu : §f" + args[0]));
            return false;
        }

        Integer duration = parseInt(player, args[1]);
        if (duration == null) return false;
        Integer level = parseInt(player, args[2]);
        if (level == null) return false;
        if (duration <= 0 || level <= 0) {
            player.sendMessage(Text.error("La durée et le niveau doivent être positifs."));
            return false;
        }

        player.addPotionEffect(new PotionEffect(type, duration * 20, level - 1), true);
        player.sendMessage(Text.success("Effet §f" + args[0].toLowerCase(Locale.ROOT)
                + " " + level + " §aappliqué pendant §f" + duration + " s§a."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("clear".startsWith(prefix)) matches.add("clear");
            for (PotionEffectType type : PotionEffectType.values()) {
                if (type == null) continue; // le tableau 1.8 contient des trous
                String name = type.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix)) matches.add(name);
            }
        }
        return matches;
    }
}
