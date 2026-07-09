package fr.redconflict.essentials.command.world;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.WeatherService;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /weather &lt;sun|rain|storm&gt; — change la météo du monde courant (admin).
 * Lève temporairement le verrou anti-pluie via {@link WeatherService} pour que
 * {@code WeatherLockListener} laisse passer la météo forcée.
 */
public class WeatherCommand extends EssCommand {

    private final WeatherService weather;

    public WeatherCommand(CommandEnvironment env, WeatherService weather) {
        super(env, "weather", true, false);
        this.weather = weather;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /weather <sun|rain|storm>"));
            return false;
        }

        World world = player.getWorld();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sun": case "clear": case "soleil":
                weather.clearOverride(world.getName());
                world.setStorm(false);
                world.setThundering(false);
                player.sendMessage(Text.success("Beau temps sur §f" + world.getName() + "§a."));
                return true;

            case "rain": case "pluie":
                weather.allowRain(world.getName());
                world.setStorm(true);
                world.setThundering(false);
                player.sendMessage(Text.success("Pluie sur §f" + world.getName() + "§a."));
                return true;

            case "storm": case "thunder": case "orage":
                weather.allowRain(world.getName());
                world.setStorm(true);
                world.setThundering(true);
                player.sendMessage(Text.success("Orage sur §f" + world.getName() + "§a."));
                return true;

            default:
                player.sendMessage(Text.error("Usage : /weather <sun|rain|storm>"));
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            for (String option : Arrays.asList("sun", "rain", "storm")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) matches.add(option);
            }
        }
        return matches;
    }
}
