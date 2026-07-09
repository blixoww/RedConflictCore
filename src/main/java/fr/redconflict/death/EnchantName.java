package fr.redconflict.death;

/** Traductions françaises des enchantements affichés dans les messages de mort. */
public enum EnchantName {
    DAMAGE_ALL("Tranchant"),
    DURABILITY("Durabilité"),
    LOOT_BONUS_MOBS("Butin"),
    DIG_SPEED("Efficacité"),
    DAMAGE_UNDEAD("Châtiment"),
    DAMAGE_ARTHROPODS("Fléau des arthropodes"),
    KNOCKBACK("Recul"),
    FIRE_ASPECT("Aspect du feu");

    private final String name;

    EnchantName(String name) {
        this.name = name;
    }

    /** @return la traduction, ou le nom Bukkit tel quel si aucune n'existe. */
    public static String of(String bukkitName) {
        try {
            return valueOf(bukkitName).name;
        } catch (IllegalArgumentException e) {
            return bukkitName;
        }
    }
}
