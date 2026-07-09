package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.WarpService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /setwarp <nom> — crée ou déplace un warp à la position actuelle (admin).
 */
public class SetWarpCommand extends EssCommand {

    private final WarpService warps;

    public SetWarpCommand(CommandEnvironment env, WarpService warps) {
        super(env, "setwarp", true, false);
        this.warps = warps;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /setwarp <nom>"));
            return false;
        }
        if (!warps.set(args[0], player.getLocation())) {
            player.sendMessage(Text.error("Nom invalide (lettres, chiffres, - et _, 16 caractères max)."));
            return false;
        }
        player.sendMessage(Text.success("Warp §f" + WarpService.normalize(args[0]) + " §adéfini ici."));
        return true;
    }
}
