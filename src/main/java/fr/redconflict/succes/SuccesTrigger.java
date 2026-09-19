package fr.redconflict.succes;

import java.util.Locale;

/**
 * Ce qui fait avancer un succès.
 *
 * <p>Un succès ne connaît pas le code qui le déclenche : il déclare un
 * compteur et un objectif, et le module branche les compteurs sur les
 * événements du jeu. Ajouter un succès sur un déclencheur existant se fait donc
 * entièrement dans {@code succes/succes.yml}, sans toucher à une ligne de Java.
 *
 * <p>Deux familles :
 * <ul>
 *   <li><b>cumulatives</b> — chaque événement ajoute au total (blocs minés,
 *       kills, argent dépensé) ;</li>
 *   <li><b>de record</b> ({@link #isRecord()}) — le compteur retient la plus
 *       grande valeur atteinte, jamais la somme. Une série de kills de 10 puis
 *       une de 7 valent 10, pas 17.</li>
 * </ul>
 */
public enum SuccesTrigger {

    /** Bloc cassé. Cible : nom du matériau. */
    BLOCK_MINE,
    /** Bloc posé. Cible : nom du matériau. */
    BLOCK_PLACE,
    /** Objet fabriqué (quantité réellement produite). Cible : nom du matériau. */
    CRAFT,
    /** Nourriture ou potion consommée. Cible : nom du matériau. */
    CONSUME,
    /** Enchantement appliqué à la table. Sans cible. */
    ENCHANT,
    /** Mort du joueur, toutes causes confondues. Sans cible. */
    DEATH,
    /** Joueur tué par le joueur. Sans cible. */
    KILL_PLAYER,
    /** Plus longue série de kills sans mourir. Record. Sans cible. */
    KILLSTREAK,
    /** Objets achetés à l'hôtel des ventes (quantité de l'annonce). */
    HDV_BUY,
    /** Objets vendus à l'hôtel des ventes (quantité de l'annonce). */
    HDV_SELL,
    /** Argent dépensé, toutes boutiques confondues. */
    MONEY_SPENT,
    /** Niveau de métier atteint. Record. Cible : MINER, FARMER, ARTISAN ou ANY. */
    JOB_LEVEL,
    /**
     * Minutes de jeu cumulées, relevées sur le compteur du serveur.
     *
     * <p>C'est un compteur de <b>record</b>, et c'est délibéré : on annonce le
     * total réel du joueur, pas « une minute de plus ». Compter les minutes
     * depuis l'installation du module aurait ignoré tout le temps déjà joué —
     * un joueur avec deux jours au compteur serait reparti de zéro.
     */
    PLAYTIME_MIN;

    /**
     * Vrai si le compteur retient un maximum au lieu d'une somme.
     *
     * <p>Ces déclencheurs annoncent une valeur ATTEINTE, pas un gain : les
     * rejouer ne double donc jamais rien, et ils peuvent être relevés
     * périodiquement sur une source extérieure.
     */
    public boolean isRecord() {
        return this == KILLSTREAK || this == JOB_LEVEL || this == PLAYTIME_MIN;
    }

    /** Vrai si le déclencheur a besoin d'une cible (matériau, métier...). */
    public boolean needsTarget() {
        return this == BLOCK_MINE || this == BLOCK_PLACE || this == CRAFT
                || this == CONSUME || this == JOB_LEVEL;
    }

    /** @return la valeur nommée, ou {@code null} si le nom est inconnu. */
    public static SuccesTrigger parse(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
