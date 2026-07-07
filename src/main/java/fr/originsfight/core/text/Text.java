package fr.originsfight.core.text;

/**
 * Palette et helpers de formatage des messages joueurs.
 *
 * <p>Conventions serveur : {@code §c} rouge (erreur/accent), {@code §f} blanc (valeur),
 * {@code §7} gris (texte secondaire), {@code §a} vert (succès).
 */
public final class Text {

    /** Préfixe officiel des messages RED CONFLICT. */
    public static final String PREFIX = "§c§lRED §f§lCONFLICT §7» §f";

    private Text() {
    }

    public static String error(String message) {
        return PREFIX + "§c" + message;
    }

    public static String success(String message) {
        return PREFIX + "§a" + message;
    }

    public static String info(String message) {
        return PREFIX + "§7" + message;
    }

    /** Formate un message avec {@link String#format(String, Object...)}. */
    public static String fmt(String template, Object... args) {
        return String.format(template, args);
    }

    /** Formate une durée en millisecondes en chaîne compacte : "1h 4m 12s", "45s"... */
    public static String duration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }

    /** Formate une durée écoulée depuis un timestamp epoch (ms) : "il y a 3h 12m". */
    public static String since(long epochMillis) {
        return "il y a " + duration(System.currentTimeMillis() - epochMillis);
    }
}
