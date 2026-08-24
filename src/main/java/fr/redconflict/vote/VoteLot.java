package fr.redconflict.vote;

import java.util.Collections;
import java.util.List;

/**
 * Un lot de la table de butin des votes.
 *
 * <p>Volontairement proche d'un article de boutique : mêmes commandes, même
 * distributeur. C'est ce qui permet aux items propres au serveur — RUBY_SWORD,
 * COBALT_SWORD — d'être donnés correctement, alors qu'un {@code give} envoyé à
 * la console ne les connaîtrait pas.
 */
public final class VoteLot {

    public final String id;
    public final String nom;
    public final int poids;
    /** PB propres au lot, qui s'ajoutent à ceux du vote. */
    public final int pb;
    public final List<String> commandes;

    VoteLot(String id, String nom, int poids, int pb, List<String> commandes) {
        this.id = id;
        this.nom = nom;
        this.poids = Math.max(0, poids);
        this.pb = Math.max(0, pb);
        this.commandes = Collections.unmodifiableList(commandes);
    }

    /**
     * Le lot dépose-t-il quelque chose dans l'inventaire ?
     *
     * <p>Un vote arrive souvent alors que le joueur n'est pas connecté — il vote
     * depuis le site. Les PB peuvent être crédités sans lui ; les objets, non.
     * C'est ce que ce test permet de distinguer.
     */
    public boolean exigeConnecte() {
        for (String ligne : commandes) {
            String[] t = ligne.trim().split("\\s+");
            if (t.length < 2) continue;
            String tete = t[0].toLowerCase(java.util.Locale.ROOT);
            int ns = tete.indexOf(':');
            if (ns >= 0) tete = tete.substring(ns + 1);
            // `give` dépose dans l'inventaire ; `givekey` aussi, selon le plugin
            // de clés. Les deux attendent donc le joueur.
            if (tete.equals("give") || tete.equals("givekey")) return true;
        }
        return false;
    }
}
