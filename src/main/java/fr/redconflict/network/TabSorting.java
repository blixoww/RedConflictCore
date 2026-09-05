package fr.redconflict.network;

/**
 * Règles de tri du tab-list, partagées par les joueurs locaux et par ceux des
 * autres serveurs de la grappe.
 *
 * <p>Le client 1.8 trie le tab sur le NOM DE LA TEAM, puis sur le pseudo — jamais
 * sur le nom affiché. Un joueur du Minage injecté dans le tab du Faction doit donc
 * entrer dans une team nommée exactement comme le ferait un joueur local de même
 * rang, sinon les deux serveurs afficheraient les mêmes joueurs dans deux ordres
 * différents. C'est toute la raison d'être de cette classe : une seule
 * implémentation, deux appelants.
 */
public final class TabSorting {

    /** Vanish ou staffmode : tout en haut, et réservé aux yeux du staff. */
    public static final String VANISH = "00_";

    /** Staff visible. */
    public static final String STAFF = "10_";

    /** Joueur ordinaire. */
    public static final String PLAYER = "20_";

    private TabSorting() {
    }

    /**
     * Nom de team unique et court (16 caractères maximum, limite du paquet
     * scoreboard 1.8).
     *
     * <p>Tronquer {@code sortPrefix + pseudo} à 16 faisait collisionner deux joueurs
     * dont les noms partagent leurs 13 premières lettres : ils atterrissaient dans la
     * même team, donc avec le même préfixe. On remplace la fin par une empreinte du
     * nom complet quand il faut couper.
     */
    public static String teamName(String sortPrefix, String playerName) {
        String full = sortPrefix + playerName;
        if (full.length() <= 16) {
            return full;
        }
        String hash = Integer.toHexString(playerName.hashCode());
        int keep = 16 - sortPrefix.length() - hash.length();
        if (keep < 0) {
            keep = 0;
        }
        return sortPrefix + playerName.substring(0, Math.min(keep, playerName.length())) + hash;
    }
}
