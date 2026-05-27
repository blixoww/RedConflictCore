package fr.originsfight.job;

/** Énumère les trois métiers du serveur (+ NONE = sans métier). */
public enum JobType {

    NONE("Aucun", "§8", "none"),
    MINER("Mineur", "§7", "miner"),
    FARMER("Agriculteur", "§a", "farmer"),
    ARTISAN("Artisan", "§6", "artisan");

    public final String displayName;
    public final String color;
    public final String key;

    JobType(String displayName, String color, String key) {
        this.displayName = displayName;
        this.color       = color;
        this.key         = key;
    }

    /** Résout un String (insensible à la casse) → JobType, NONE si inconnu. */
    public static JobType fromString(String s) {
        if (s == null || s.isEmpty()) return NONE;
        for (JobType jt : values()) {
            if (jt.name().equalsIgnoreCase(s) || jt.key.equalsIgnoreCase(s)) return jt;
        }
        return NONE;
    }

    /** Retour vrai si ce type est un vrai métier (≠ NONE). */
    public boolean isReal() { return this != NONE; }
}

