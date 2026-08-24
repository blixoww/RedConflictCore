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

        String modele = cfg.getString("boutique.annonce", "");
        if (modele == null || modele.trim().isEmpty()) return;

        String duree = permanent
                ? cfg.getString("boutique.annonce_a_vie", "à vie")
                : cfg.getString("boutique.annonce_temporaire", "pour 30 jours");

        String message = modele
                .replace("%player%", pseudo)
                .replace("%article%", article)
                .replace("%duree%", duree);

        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
