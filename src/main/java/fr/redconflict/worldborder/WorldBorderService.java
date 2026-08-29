package fr.redconflict.worldborder;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Applique la bordure du monde à chaque monde concerné.
 *
 * <p><b>Pourquoi la bordure vanilla.</b> On ne pose aucun bloc et on ne surveille
 * aucun déplacement : c'est le client qui dessine le mur translucide animé et le
 * voile rouge d'approche, et le serveur qui repousse le joueur. Coût serveur nul,
 * rendu identique au vanilla, et rien à nettoyer si la taille change. Un mur de
 * blocs barrière ferait des milliers de blocs à poser, à sauvegarder et à
 * reconstruire au moindre agrandissement.
 *
 * <p><b>La bordure est persistée dans le {@code level.dat} du monde</b>, donc elle
 * survit à un redémarrage même plugin retiré. C'est pourquoi les cas « désactivé »
 * et « monde non listé » ne se contentent pas de ne rien faire : ils remettent
 * explicitement la bordure par défaut. Sans ça, une bordure posée une fois
 * resterait pour toujours et {@code enabled: false} n'aurait aucun effet visible.
 */
public class WorldBorderService {

    /** Côté du carré, en blocs : 10000 = de -5000 à +5000 autour du centre. */
    private static final double DEFAULT_SIZE = 10000.0D;

    private final JavaPlugin plugin;

    public WorldBorderService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Applique la configuration à tous les mondes déjà chargés. */
    public void applyAll() {
        for (World world : Bukkit.getWorlds()) {
            apply(world);
        }
    }

    /** Applique la configuration à un monde, ou lui rend la bordure par défaut. */
    public void apply(World world) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("worldborder");
        WorldBorder border = world.getWorldBorder();

        if (cfg == null || !cfg.getBoolean("enabled", true) || !concerns(cfg, world)) {
            border.reset();
            return;
        }

        border.setCenter(cfg.getDouble("center.x", 0.0D), cfg.getDouble("center.z", 0.0D));
        border.setSize(cfg.getDouble("size", DEFAULT_SIZE));
        border.setDamageAmount(cfg.getDouble("damage-amount", 0.2D));
        border.setDamageBuffer(cfg.getDouble("damage-buffer", 5.0D));
        border.setWarningDistance(cfg.getInt("warning-distance", 32));
        border.setWarningTime(cfg.getInt("warning-time", 15));
    }

    /** Taille configurée, pour les logs du module. */
    public double size() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("worldborder");
        return cfg == null ? DEFAULT_SIZE : cfg.getDouble("size", DEFAULT_SIZE);
    }

    public boolean isEnabled() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("worldborder");
        return cfg != null && cfg.getBoolean("enabled", true);
    }

    /**
     * Avertit si le /rtp peut déposer un joueur hors de la bordure.
     *
     * <p>{@code rtp.max} est une distance au centre, la bordure s'arrête à la
     * moitié de son côté : {@code rtp.max: 9000} contre une bordure de 10000
     * envoie le joueur jusqu'à 4000 blocs dehors, où il prendra des dégâts sans
     * comprendre pourquoi. Le contrôle est volontairement approximatif — il
     * suppose le centre du RTP confondu avec celui de la bordure — mais il attrape
     * le cas qui compte, celui où les deux réglages ont été posés séparément.
     */
    public void warnIfRtpOutOfBounds() {
        if (!isEnabled()) {
            return;
        }
        long halfSize = (long) (size() / 2.0D);
        int rtpMax = plugin.getConfig().getInt("rtp.max", 0);
        if (rtpMax > halfSize) {
            plugin.getLogger().warning("[Bordure] rtp.max vaut " + rtpMax + " alors que la bordure "
                    + "s'arrête à " + halfSize + " blocs du centre : le /rtp peut déposer un joueur "
                    + "dehors, qui prendra des dégâts et sera repoussé. Abaissez rtp.max à "
                    + halfSize + " au plus, ou agrandissez worldborder.size.");
        }
    }

    /** Un {@code worlds} vide vaut « tous les mondes ». */
    private boolean concerns(ConfigurationSection cfg, World world) {
        List<String> worlds = cfg.getStringList("worlds");
        if (worlds == null || worlds.isEmpty()) {
            return true;
        }
        for (String name : worlds) {
            if (name.equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }
}
