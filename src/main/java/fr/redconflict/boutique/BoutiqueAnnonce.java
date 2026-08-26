package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Annonce d'un achat à tout le serveur.
 *
 * <p>Un seul endroit pour ce message, appelé depuis les <b>quatre</b> chemins
 * qui aboutissent à une vente : le comptoir en jeu, une offre spéciale, une
 * livraison venue du site, et la reprise d'une commande restée en attente.
 * Annoncer les achats en jeu et taire ceux du site aurait donné l'impression
 * d'une boutique deux fois moins vivante qu'elle ne l'est.
 *
 * <p>Message vide en configuration = aucune annonce. C'est le réglage à choisir
 * si la boutique tourne beaucoup : un chat noyé sous les annonces finit par être
 * coupé par les joueurs, et on perd l'effet recherché.
 */
public final class BoutiqueAnnonce {

    /**
     * Modele de repli, identique a celui du {@code boutique.yml} embarque.
     *
     * <p>Il sert quand la cle est <b>absente</b> — un fichier de configuration
     * plus ancien que la fonctionnalite. Une cle presente mais vide reste, elle,
     * une extinction volontaire : c'est la difference entre « pas encore
     * configure » et « je n'en veux pas ».
     */
    private static final String MODELE_DEFAUT =
            "&7[&c✦&7] &f%player% &7vient d'acheter &c%article% &7%duree% !";
    private static final String DUREE_DEFAUT_PERMANENT = "&8(a vie)";
    private static final String DUREE_DEFAUT_TEMPORAIRE = "&8(30 jours)";

    private BoutiqueAnnonce() { }

    /**
     * Diffuse l'annonce. À appeler sur le thread principal.
     *
     * @param article  nom lisible, sans code couleur
     * @param permanent achat à vie, ou location
     */
    public static void annoncer(RedConflictCore plugin, String pseudo, String article, boolean permanent) {
        FileConfiguration cfg = plugin.getBoutiqueConfig();
        if (cfg == null) return;

        String modele = cfg.getString("boutique.annonce", MODELE_DEFAUT);
        if (modele == null || modele.trim().isEmpty()) return;

        String duree = permanent
                ? cfg.getString("boutique.annonce_a_vie", DUREE_DEFAUT_PERMANENT)
                : cfg.getString("boutique.annonce_temporaire", DUREE_DEFAUT_TEMPORAIRE);

        String message = modele
                .replace("%player%", pseudo)
                .replace("%article%", article)
                .replace("%duree%", duree);

        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
