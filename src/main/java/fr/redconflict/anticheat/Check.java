package fr.redconflict.anticheat;

/**
 * Les contrôles, et ce que chacun sait faire.
 *
 * <p>Le libellé sert aux alertes staff ; la clé de configuration nomme la
 * section {@code anticheat.<clé>} qui porte son seuil et son activation.
 *
 * <p>Un mot sur la confiance qu'on peut leur accorder. Ces contrôles vivent sur
 * le SERVEUR et jugent des faits que le serveur observe lui-même : une distance,
 * un débit, un bloc. Aucun client, si modifié soit-il, ne peut les désactiver —
 * au mieux il peut rester sous les seuils, ce qui revient à ne plus tricher.
 * C'est la différence de nature avec tout ce qu'on peut mettre dans le client,
 * qui n'est qu'un ralentisseur.
 */
public enum Check {

    /** Déplacement horizontal soutenu au-delà de ce que le jeu permet. */
    SPEED("speed", "Vitesse"),

    /**
     * Cadence de paquets de mouvement anormale. C'est la signature du
     * « timer » : le client fait tourner sa boucle plus vite que 20 tps et
     * envoie donc plus de positions par seconde. Le déplacement par paquet
     * reste légal, seul le nombre trahit.
     */
    TIMER("timer", "Timer"),

    /** Maintien ou gain d'altitude sans support ni cause connue. */
    FLY("fly", "Vol"),

    /**
     * Le client annonce toucher le sol alors qu'il n'y a rien sous lui.
     * C'est ce mensonge qui annule les dégâts de chute.
     */
    NOFALL("nofall", "NoFall"),

    /** Coup porté au-delà de l'allonge du jeu. */
    REACH("reach", "Allonge"),

    /** Cadence de coups au-delà de ce qu'une main humaine produit. */
    AUTOCLICK("autoclick", "Clics"),

    /** Coup porté à travers un bloc plein. */
    THROUGH_WALL("through-wall", "Coup à travers un mur"),

    /** Blocs cassés trop vite, ou trop loin. */
    NUKER("nuker", "Minage anormal"),

    /** Proportion de minerais rares trop élevée pour être due au hasard. */
    XRAY("xray", "X-ray probable"),

    /** Messages de canal trop gros ou trop nombreux. */
    CHANNEL_ABUSE("channels", "Abus de canal"),

    /**
     * Le client n'a pas su prouver l'intégrité de son jar, ou n'a pas répondu au
     * défi. Alerte uniquement : le serveur ne doit JAMAIS dire au tricheur que
     * sa réponse est fausse, sinon il itère jusqu'à trouver.
     */
    ATTESTATION("attestation", "Intégrité du client"),

    /**
     * Le client a signalé lui-même un environnement d'injection (agent Java,
     * débogueur attaché). Signal utile mais non fiable : un client modifié se
     * contente de ne rien signaler.
     */
    CLIENT_TAMPER("client-report", "Client instrumenté");

    private final String key;
    private final String label;

    Check(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Nom de la section de configuration : {@code anticheat.<key>}. */
    public String key() {
        return key;
    }

    /** Libellé affiché au staff. */
    public String label() {
        return label;
    }
}
