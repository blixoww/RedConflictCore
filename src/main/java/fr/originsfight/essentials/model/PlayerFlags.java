package fr.originsfight.essentials.model;

/**
 * États persistants d'un joueur (DTO) : mode dieu et vol.
 */
public final class PlayerFlags {

    public static final PlayerFlags NONE = new PlayerFlags(false, false);

    private final boolean god;
    private final boolean fly;

    public PlayerFlags(boolean god, boolean fly) {
        this.god = god;
        this.fly = fly;
    }

    public boolean isGod() {
        return god;
    }

    public boolean isFly() {
        return fly;
    }
}
