package fr.redconflict.combat;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;

/**
 * Module combat : réglage du recul au corps à corps et boost à l'arc.
 *
 * <p>Le module s'installe toujours ; c'est {@code combat.knockback.enabled} qui
 * décide, et il est relu à chaque coup — un {@code /red reload} suffit donc à
 * changer le réglage, sans redémarrage.
 */
public class KnockbackModule implements Module {

    private final RedConflictCore plugin;

    public KnockbackModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Knockback";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(new KnockbackListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new BowBoostListener(plugin), plugin);
        if (plugin.getConfig().getBoolean("combat.knockback.enabled", true)) {
            plugin.getLogger().info(String.format(
                    "[Combat] Recul réduit — horizontal %.2f, vertical %.2f, enchantement %.2f, "
                            + "garanti à la charge %.2f.",
                    plugin.getConfig().getDouble("combat.knockback.horizontal", 0.55),
                    plugin.getConfig().getDouble("combat.knockback.vertical", 0.75),
                    plugin.getConfig().getDouble("combat.knockback.enchant", 0.15),
                    plugin.getConfig().getDouble("combat.knockback.minimum", 1.0)));
        }
        if (plugin.getConfig().getBoolean("combat.bow-boost.enabled", true)) {
            plugin.getLogger().info(String.format(
                    "[Combat] Boost à l'arc — socle %.2f, %.2f par niveau de Punch, saut %.2f.",
                    plugin.getConfig().getDouble("combat.bow-boost.horizontal", 0.35),
                    plugin.getConfig().getDouble("combat.bow-boost.per-punch", 0.60),
                    plugin.getConfig().getDouble("combat.bow-boost.vertical", 0.40)));
        }
    }
}
