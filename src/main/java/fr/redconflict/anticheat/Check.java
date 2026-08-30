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

    /**
     * Coup porté sur une cible que le joueur ne regardait pas.
     *
     * <p>C'est LE contrôle qui attrape l'aura moderne, celle qui reste sous
     * tous les seuils de quantité : elle n'attaque qu'à cadence humaine, à
     * portée légale, sans mur — mais elle désigne une entité que le curseur n'a
     * jamais visée. Un client vanilla ne peut pas produire ce paquet.
     */
    AIM("aim", "Visée"),

    /**
     * Coup porté sans animation de bras. Le client vanilla en envoie une à
     * chaque clic ; une aura qui fabrique le seul paquet d'attaque n'en envoie
     * aucune.
     */
    NO_SWING("no-swing", "Coup sans animation"),

    /**
     * Plusieurs joueurs distincts touchés en quelques ticks : personne ne
     * déplace son curseur assez vite pour ça.
     */
    MULTI_AURA("multi-aura", "Aura multi-cibles"),

    /** Blocs cassés trop vite, ou trop loin. */
    NUKER("nuker", "Minage anormal"),

    /** Proportion de minerais rares trop élevée pour être due au hasard. */
    XRAY("xray", "X-ray probable"),

    /**
     * Coup porte sur une entite fantome placee hors du champ de vision.
     *
     * <p>Le seul controle du module qui produise une PREUVE et non un indice :
     * il ne mesure aucune grandeur et ne compare a aucun seuil, il constate une
     * impossibilite geometrique. Voir {@link HoneypotCheck}.
     */
    HONEYPOT("honeypot", "Entite fantome"),

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
     *
     * <p>Motifs MOUS uniquement — au premier rang « poignée de main du launcher
     * absente », qui se lève chez TOUT joueur tant que le nouveau launcher n'est
     * pas déployé. À laisser en alerte, jamais en kick, sinon on expulse les
     * joueurs légitimes.
     */
    CLIENT_TAMPER("client-report", "Client instrumenté"),

    /**
     * Le client a signalé un motif DUR : du code chargé hors du jar officiel, ou
     * un agent d'instrumentation. Un joueur légitime ne les déclenche jamais —
     * il ne charge que le jar signé et ne passe pas de {@code -javaagent}. On
     * peut donc agir dessus (kick) sans risque de faux positif sur un honnête.
     *
     * <p>Limite à garder en tête : c'est auto-déclaré. Un client qui fait taire
     * son propre rapport n'apparaît pas ici — d'où l'importance des contrôles
     * serveur autoritatifs à côté (masquage anti-ESP, validation des paquets).
     * Kicker ici apprend aussi au tricheur qu'il est vu : utile contre les
     * outils tout faits, sans illusion sur les plus tenaces.
     */
    CLIENT_INJECTION("client-injection", "Client injecté");

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
