package fr.originsfight.essentials.service.resolve;

import org.bukkit.enchantments.Enchantment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Résolution des enchantements pour /enchant : alias usuels (noms Essentials/vanilla)
 * en plus des noms Bukkit bruts (DAMAGE_ALL, DIG_SPEED...).
 */
public class EnchantmentResolver {

    private final Map<String, Enchantment> aliases = new HashMap<>();

    public EnchantmentResolver() {
        alias("sharpness", Enchantment.DAMAGE_ALL);
        alias("smite", Enchantment.DAMAGE_UNDEAD);
        alias("baneofarthropods", Enchantment.DAMAGE_ARTHROPODS);
        alias("knockback", Enchantment.KNOCKBACK);
        alias("fireaspect", Enchantment.FIRE_ASPECT);
        alias("looting", Enchantment.LOOT_BONUS_MOBS);
        alias("protection", Enchantment.PROTECTION_ENVIRONMENTAL);
        alias("fireprotection", Enchantment.PROTECTION_FIRE);
        alias("blastprotection", Enchantment.PROTECTION_EXPLOSIONS);
        alias("projectileprotection", Enchantment.PROTECTION_PROJECTILE);
        alias("featherfalling", Enchantment.PROTECTION_FALL);
        alias("respiration", Enchantment.OXYGEN);
        alias("aquaaffinity", Enchantment.WATER_WORKER);
        alias("thorns", Enchantment.THORNS);
        alias("depthstrider", Enchantment.DEPTH_STRIDER);
        alias("efficiency", Enchantment.DIG_SPEED);
        alias("silktouch", Enchantment.SILK_TOUCH);
        alias("unbreaking", Enchantment.DURABILITY);
        alias("fortune", Enchantment.LOOT_BONUS_BLOCKS);
        alias("power", Enchantment.ARROW_DAMAGE);
        alias("punch", Enchantment.ARROW_KNOCKBACK);
        alias("flame", Enchantment.ARROW_FIRE);
        alias("infinity", Enchantment.ARROW_INFINITE);
        alias("luckofthesea", Enchantment.LUCK);
        alias("lure", Enchantment.LURE);
    }

    private void alias(String name, Enchantment enchantment) {
        aliases.put(name, enchantment);
    }

    /** @return l'enchantement, ou {@code null} si inconnu. */
    public Enchantment resolve(String token) {
        String key = token.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        Enchantment byAlias = aliases.get(key);
        if (byAlias != null) return byAlias;
        return Enchantment.getByName(token.toUpperCase(Locale.ROOT));
    }
}
