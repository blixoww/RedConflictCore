package fr.originsfight.essentials;

import fr.originsfight.core.text.Text;

/**
 * Vocabulaire commun du module essentials (préfixe et palette officiels du serveur).
 * Les textes propres à une seule commande restent dans la commande concernée,
 * construits via {@link Text} pour garder une palette homogène.
 */
public final class Messages {

    public static final String PREFIX = Text.PREFIX;

    // Génériques
    public static final String ERR_PLAYER_ONLY   = Text.error("Cette commande est réservée aux joueurs.");
    public static final String ERR_PLAYER_NOT_FOUND = Text.error("Joueur introuvable ou hors ligne.");
    public static final String ERR_INVALID_NUMBER   = Text.error("Nombre invalide.");
    public static final String ERR_INTERNAL         = Text.error("Erreur interne — contactez un administrateur.");
    /** %s = temps restant. */
    public static final String ERR_COOLDOWN         = Text.error("Vous devez attendre §f%s §cavant de réutiliser cette commande.");
    public static final String ERR_NO_PERM_OTHERS   = Text.error("Vous n'avez pas la permission de cibler un autre joueur.");

    // Téléportation
    /** %d = secondes. */
    public static final String TP_WARMUP           = Text.info("Téléportation dans §f%d s §7— ne bougez pas !");
    public static final String TP_CANCELLED_MOVE   = Text.error("Téléportation annulée : vous avez bougé.");
    public static final String TP_CANCELLED_DAMAGE = Text.error("Téléportation annulée : vous avez subi des dégâts.");
    public static final String TP_DONE             = Text.success("Téléportation effectuée.");
    public static final String TP_DEST_INVALID     = Text.error("Destination invalide (monde non chargé ?).");

    private Messages() {
    }

    /** Formate un message avec {@link String#format(String, Object...)}. */
    public static String fmt(String template, Object... args) {
        return String.format(template, args);
    }
}