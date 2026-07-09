package fr.redconflict.essentials.command.player;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /speed &lt;1-10|reset&gt; [joueur] — vitesse de déplacement. S'applique au vol
 * si le joueur vole, à la marche sinon (comportement Essentials).
 * La valeur maximale est bornée par {@code speed.max} dans essentials.yml.
 */
public class SpeedCommand extends EssCommand {

    /** Vitesses vanilla : marche 0.2 (= 2), vol 0.1 (= 1). */
    private static final float DEFAULT_WALK_SPEED = 0.2f;
    private static final float DEFAULT_FLY_SPEED = 0.1f;

    public SpeedCommand(CommandEnvironment env) {
        super(env, "speed", true, false);
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            player.sendMessage(Text.error("Usage : /speed <1-" + env.getConfig().speedMax() + "|reset> [joueur]"));
            return false;
        }

        Player target = player;
        if (args.length >= 2) {
            if (!checkOthers(player, "redconflict.speed")) return false;
            target = findOnline(player, args[1]);
            if (target == null) return false;
        }

        boolean flying = target.isFlying();
        if (args[0].equalsIgnoreCase("reset")) {
            target.setWalkSpeed(DEFAULT_WALK_SPEED);
            target.setFlySpeed(DEFAULT_FLY_SPEED);
            notifyChange(player, target, flying, "réinitialisée");
            return true;
        }

        Integer value = parseInt(player, args[0]);
        if (value == null) return false;
        int max = env.getConfig().speedMax();
        if (value < 1 || value > max) {
            player.sendMessage(Text.error("La vitesse doit être entre §f1 §cet §f" + max + "§c."));
            return false;
        }

        float speed = value / 10f;
        if (flying) {
            target.setFlySpeed(speed);
        } else {
            target.setWalkSpeed(speed);
        }
        notifyChange(player, target, flying, "réglée sur §f" + value);
        return true;
    }

    private void notifyChange(Player sender, Player target, boolean flying, String change) {
        String kind = flying ? "de vol" : "de marche";
        target.sendMessage(Text.success("Vitesse " + kind + " " + change + "§a."));
        if (sender != target) {
            sender.sendMessage(Text.success("Vitesse " + kind + " de §f" + target.getName() + " §a" + change + "§a."));
        }
    }
}
