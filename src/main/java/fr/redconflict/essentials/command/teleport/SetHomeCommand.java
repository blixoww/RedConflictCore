package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.HomeService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /sethome [nom] — définit un home à la position actuelle ("home" par défaut).
 * Le nombre de homes dépend des permissions redconflict.sethome.multiple.X.
 */
public class SetHomeCommand extends EssCommand {

    private final HomeService homes;

    public SetHomeCommand(CommandEnvironment env, HomeService homes) {
        super(env, "sethome", true, true);
        this.homes = homes;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        String name = args.length >= 1 ? args[0] : "home";

        switch (homes.set(player, name)) {
            case CREATED:
                player.sendMessage(Text.success("Home §f" + HomeService.normalize(name) + " §adéfini."));
                return true;
            case REPLACED:
                player.sendMessage(Text.success("Home §f" + HomeService.normalize(name) + " §aredéfini ici."));
                return true;
            case INVALID_NAME:
                player.sendMessage(Text.error("Nom invalide (lettres, chiffres, - et _, 16 caractères max)."));
                return false;
            case LIMIT_REACHED:
            default:
                player.sendMessage(Text.error("Limite de homes atteinte (§f"
                        + homes.maxHomes(player) + "§c). Supprimez-en un avec §f/delhome§c."));
                return false;
        }
    }
}
