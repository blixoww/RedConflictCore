package fr.redconflict.essentials.command.teleport;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.Messages;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * /tp — téléportation directe (admin, sans délai).
 *
 * <p>Quatre formes, distinguées par le seul nombre d'arguments :
 * <pre>
 * /tp &lt;joueur&gt;                 l'exécutant vers ce joueur
 * /tp &lt;joueur&gt; &lt;joueur2&gt;       le premier joueur vers le second
 * /tp &lt;x&gt; &lt;y&gt; &lt;z&gt;             l'exécutant vers ces coordonnées
 * /tp &lt;joueur&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;    ce joueur vers ces coordonnées
 * </pre>
 *
 * <p>Le compte d'arguments suffit à lever l'ambiguïté : trois arguments ne peuvent
 * être qu'un triplet de coordonnées, quatre qu'un joueur suivi d'un triplet. Pas
 * d'heuristique du genre « ça ressemble à un nombre » — un joueur nommé {@code 12}
 * resterait un joueur.
 *
 * <p>La destination est prise dans le monde du joueur DÉPLACÉ, et son orientation
 * est conservée : téléporter quelqu'un par coordonnées ne doit ni le changer de
 * dimension ni lui retourner la vue.
 */
public class TpCommand extends EssCommand {

    /** Limite du monde vanilla. Au-delà, le client décroche au lieu de se déplacer. */
    private static final double COORD_LIMIT = 30000000D;

    private final TeleportService teleports;

    public TpCommand(CommandEnvironment env, TeleportService teleports) {
        super(env, "tp", false, false);
        this.teleports = teleports;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        switch (args.length) {
            case 1:
                return selfToPlayer(sender, args);
            case 2:
                return playerToPlayer(sender, args);
            case 3:
                return selfToCoords(sender, args);
            case 4:
                return playerToCoords(sender, args);
            default:
                return usage(sender);
        }
    }

    // ── Les quatre formes ──────────────────────────────────────────────────────

    /** /tp &lt;joueur&gt; */
    private boolean selfToPlayer(CommandSender sender, String[] args) {
        Player self = requirePlayer(sender);
        if (self == null) return false;
        Player target = findOnline(sender, args[0]);
        if (target == null) return false;

        teleports.teleportNow(self, target.getLocation());
        sender.sendMessage(Text.success("Téléporté vers §f" + target.getName() + "§a."));
        return true;
    }

    /** /tp &lt;joueur&gt; &lt;joueur2&gt; */
    private boolean playerToPlayer(CommandSender sender, String[] args) {
        Player moved = findOnline(sender, args[0]);
        if (moved == null) return false;
        Player destination = findOnline(sender, args[1]);
        if (destination == null) return false;

        teleports.teleportNow(moved, destination.getLocation());
        sender.sendMessage(Text.success("§f" + moved.getName() + " §atéléporté vers §f"
                + destination.getName() + "§a."));
        moved.sendMessage(Text.info("Vous avez été téléporté vers §f" + destination.getName() + "§7."));
        return true;
    }

    /** /tp &lt;x&gt; &lt;y&gt; &lt;z&gt; */
    private boolean selfToCoords(CommandSender sender, String[] args) {
        Player self = requirePlayer(sender);
        if (self == null) return false;
        Location destination = parseCoords(sender, self, args, 0);
        if (destination == null) return false;

        teleports.teleportNow(self, destination);
        sender.sendMessage(Text.success("Téléporté en §f" + coords(destination) + "§a."));
        return true;
    }

    /** /tp &lt;joueur&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; */
    private boolean playerToCoords(CommandSender sender, String[] args) {
        Player moved = findOnline(sender, args[0]);
        if (moved == null) return false;
        Location destination = parseCoords(sender, moved, args, 1);
        if (destination == null) return false;

        teleports.teleportNow(moved, destination);
        sender.sendMessage(Text.success("§f" + moved.getName() + " §atéléporté en §f"
                + coords(destination) + "§a."));
        moved.sendMessage(Text.info("Vous avez été téléporté en §f" + coords(destination) + "§7."));
        return true;
    }

    // ── Lecture des coordonnées ────────────────────────────────────────────────

    /**
     * Lit trois coordonnées à partir de {@code offset}.
     *
     * <p>Monde et orientation viennent du joueur déplacé, pas de l'exécutant : un
     * admin qui téléporte quelqu'un depuis un autre monde le déplace chez lui, il
     * ne l'aspire pas dans le sien.
     */
    private Location parseCoords(CommandSender sender, Player moved, String[] args, int offset) {
        Double x = parseCoord(sender, args[offset]);
        if (x == null) return null;
        Double y = parseCoord(sender, args[offset + 1]);
        if (y == null) return null;
        Double z = parseCoord(sender, args[offset + 2]);
        if (z == null) return null;

        Location current = moved.getLocation();
        return new Location(current.getWorld(), x, y, z, current.getYaw(), current.getPitch());
    }

    /**
     * Une coordonnée.
     *
     * <p>{@code parseAmount} du socle ne convient pas : il refuse le zéro et les
     * valeurs négatives, qui sont ici des positions parfaitement ordinaires. La
     * borne écarte les saisies qui feraient décrocher le client plutôt que de le
     * laisser partir dans le vide sur un chiffre tapé de travers.
     */
    private Double parseCoord(CommandSender sender, String raw) {
        try {
            double value = Double.parseDouble(raw.replace(",", "."));
            if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) > COORD_LIMIT) {
                sender.sendMessage(Messages.ERR_INVALID_NUMBER);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage(Messages.ERR_INVALID_NUMBER);
            return null;
        }
    }

    /** Coordonnées lisibles, au dixième de bloc. */
    private static String coords(Location location) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                location.getX(), location.getY(), location.getZ());
    }

    // ── Divers ─────────────────────────────────────────────────────────────────

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.ERR_PLAYER_ONLY);
            return null;
        }
        return (Player) sender;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Text.error("Usage : /tp <joueur> [joueur2]"));
        sender.sendMessage(Text.error("        /tp [joueur] <x> <y> <z>"));
        return false;
    }
}
