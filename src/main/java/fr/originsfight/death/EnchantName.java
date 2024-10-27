package fr.originsfight.death;

public enum EnchantName {
    DAMAGE_ALL("Tranchant"),
    DURABILITY("Durabilité"),
    LOOT_BONUS_MOBS("Butin"),
    DIG_SPEED("Efficacité"),
    DAMAGE_UNDEAD("Châtiment"),
    DAMAGE_ARTHROPODS("Fléau des arthropodes"),
    KNOCKBACK("Recul"),
    FIRE_ASPECT("Aspect du feu"),;

    private final String name;

    private EnchantName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }
}
