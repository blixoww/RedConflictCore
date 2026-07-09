package fr.redconflict.essentials.service.resolve;

import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Résolution des effets de potion pour /potion : alias usuels en plus
 * des noms Bukkit bruts (INCREASE_DAMAGE, FAST_DIGGING...).
 */
public class PotionResolver {

    private final Map<String, PotionEffectType> aliases = new HashMap<>();

    public PotionResolver() {
        alias("speed", PotionEffectType.SPEED);
        alias("slowness", PotionEffectType.SLOW);
        alias("haste", PotionEffectType.FAST_DIGGING);
        alias("fatigue", PotionEffectType.SLOW_DIGGING);
        alias("miningfatigue", PotionEffectType.SLOW_DIGGING);
        alias("strength", PotionEffectType.INCREASE_DAMAGE);
        alias("force", PotionEffectType.INCREASE_DAMAGE);
        alias("jump", PotionEffectType.JUMP);
        alias("jumpboost", PotionEffectType.JUMP);
        alias("nausea", PotionEffectType.CONFUSION);
        alias("regen", PotionEffectType.REGENERATION);
        alias("regeneration", PotionEffectType.REGENERATION);
        alias("resistance", PotionEffectType.DAMAGE_RESISTANCE);
        alias("fireresistance", PotionEffectType.FIRE_RESISTANCE);
        alias("waterbreathing", PotionEffectType.WATER_BREATHING);
        alias("invisibility", PotionEffectType.INVISIBILITY);
        alias("blindness", PotionEffectType.BLINDNESS);
        alias("nightvision", PotionEffectType.NIGHT_VISION);
        alias("hunger", PotionEffectType.HUNGER);
        alias("weakness", PotionEffectType.WEAKNESS);
        alias("poison", PotionEffectType.POISON);
        alias("wither", PotionEffectType.WITHER);
        alias("healthboost", PotionEffectType.HEALTH_BOOST);
        alias("absorption", PotionEffectType.ABSORPTION);
        alias("saturation", PotionEffectType.SATURATION);
    }

    private void alias(String name, PotionEffectType type) {
        aliases.put(name, type);
    }

    /** @return le type d'effet, ou {@code null} si inconnu. */
    public PotionEffectType resolve(String token) {
        String key = token.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        PotionEffectType byAlias = aliases.get(key);
        if (byAlias != null) return byAlias;
        return PotionEffectType.getByName(token.toUpperCase(Locale.ROOT));
    }
}
