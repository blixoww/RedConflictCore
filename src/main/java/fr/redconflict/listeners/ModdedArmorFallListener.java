package fr.redconflict.listeners;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Amorti de chute des armures moddées.
 *
 * <p><b>Pourquoi ça n'existait pas.</b> Les dégâts de chute ignorent l'armure :
 * {@code DamageSource.FALL} passe outre la réduction d'armure, dans le fork
 * comme en vanilla. Une armure ruby complète encaisse donc une chute
 * exactement comme un joueur nu — seuls Protection et Chute Amortie comptent.
 * C'est cohérent en vanilla, où l'armure la plus dure vaut 20 points ; ça l'est
 * beaucoup moins avec des paliers moddés qui montent à 25.
 *
 * <p><b>Ce que fait ce listener.</b> Il rend une part des dégâts de chute à
 * l'armure portée, pièce par pièce, sans toucher au reste du calcul : la
 * protection magique et la potion de Chute Amortie continuent de s'appliquer
 * par-dessus, et la durabilité n'est pas consommée (une chute n'use pas
 * l'armure, comme en vanilla).
 *
 * <p>Les valeurs vivent dans {@code gameplay.fall-armor} de config.yml et sont
 * relues à chaud. La clé d'une pièce est le préfixe de son matériau
 * ({@code RUBY_CHESTPLATE} → {@code ruby}), donc y ajouter une armure — moddée
 * ou vanilla — ne demande aucune ligne de code.
 */
public class ModdedArmorFallListener implements Listener {

    /**
     * Valeurs par défaut, portées ici en plus du {@code config.yml} livré.
     *
     * <p>Même raison que dans {@code HwidBanService} : on n'écrit jamais dans
     * {@code config.yml} (le réécrire perdrait ses centaines de lignes de
     * commentaires), donc un serveur déjà en service n'aura pas la section tant
     * qu'on ne l'y ajoute pas à la main. Sans ces défauts, la fonction
     * s'activerait dans le vide — aucune pièce reconnue, aucun amorti.
     */
    private static final Map<String, Double> DEFAULTS = new HashMap<String, Double>();

    static {
        DEFAULTS.put("steel", 0.06D);
        DEFAULTS.put("emerald", 0.08D);
        DEFAULTS.put("ruby", 0.10D);
        DEFAULTS.put("cobalt", 0.12D);
    }

    private boolean enabled = true;
    private double max = 0.60D;
    private Map<String, Double> perPiece = new HashMap<String, Double>(DEFAULTS);

    /** Relit {@code gameplay.fall-armor}. Appelé à l'activation et à chaque /red reload. */
    public void reload(FileConfiguration config) {
        this.enabled = config.getBoolean("gameplay.fall-armor.enabled", true);
        this.max = config.getDouble("gameplay.fall-armor.max", 0.60D);
        // Le fichier complète les défauts au lieu de les remplacer : une section
        // partielle (une seule armure retouchée) reste valable.
        Map<String, Double> values = new HashMap<String, Double>(DEFAULTS);
        ConfigurationSection section = config.getConfigurationSection("gameplay.fall-armor.per-piece");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                values.put(key.toLowerCase(Locale.ROOT), section.getDouble(key, 0.0D));
            }
        }
        this.perPiece = values;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!enabled
                || event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player)) {
            return;
        }
        double reduction = reductionFor((Player) event.getEntity());
        if (reduction <= 0.0D) {
            return;
        }
        // On agit sur les dégâts de base : Bukkit recalcule ensuite les autres
        // modificateurs (protection magique, absorption) à partir de cette valeur.
        event.setDamage(event.getDamage() * (1.0D - reduction));
    }

    /** Part des dégâts de chute absorbée par l'armure portée, plafonnée. */
    private double reductionFor(Player player) {
        double total = 0.0D;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || piece.getType() == Material.AIR) continue;
            Double value = perPiece.get(materialFamily(piece.getType()));
            if (value != null) total += value;
        }
        if (total > max) total = max;
        return total < 0.0D ? 0.0D : total;
    }

    /** {@code RUBY_CHESTPLATE} → {@code ruby} ; le nom complet s'il n'y a pas de préfixe. */
    private static String materialFamily(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT);
        int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name;
    }
}
