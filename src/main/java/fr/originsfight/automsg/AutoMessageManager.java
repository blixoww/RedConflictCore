package fr.originsfight.automsg;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.List;

/**
 * Système de messages automatiques dans le chat.
 *
 * Les messages s'affichent à intervalle régulier (configurable via INTERVAL_TICKS).
 * Modifier la liste MESSAGES pour changer les annonces.
 *
 * Intervalle par défaut : 5 minutes (6000 ticks).
 */
public class AutoMessageManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Intervalle entre chaque message en ticks (20 ticks = 1 seconde). */
    private static final long INTERVAL_TICKS = 20L * 60 * 30; // 30 minutes

    /** Séparateur affiché avant/après le message. */
    private static final String SEP = "§8--------------------------------";

    /** Préfixe du message automatique. */
    private static final String PRE = "§8[§6§lRedConflict§8] ";

    /** Liste des messages affichés en rotation. Modifiez-les librement. */
    private static final List<String> MESSAGES = Arrays.asList(
        // Message 1 : Boutique
        SEP + "\n" +
        PRE + "§eAccédez à l'Hotel des Ventes avec §f/hdv §e!\n" +
        "  §8| §7Vendez et achetez des items entre joueurs.\n" +
        SEP,

        // Message 2 : Commandes utiles
        SEP + "\n" +
        PRE + "§eCommandes utiles :\n" +
        "  §8| §f/ks        §7— Vos statistiques de combat\n" +
        "  §8| §f/bottlexp  §7— Embouteiller vos niveaux d'XP\n" +
        "  §8| §f/trade     §7— Échanger des items en sécurité\n" +
        "  §8| §f/prime     §7— Placer une prime sur un joueur\n" +
        "  §8| §f/commands  §7— Voir toutes les commandes disponibles\n" +
        SEP,

        // Message 3 : Réparer ses items
        SEP + "\n" +
        PRE + "§eVos items sont endommagés ?\n" +
        "  §8| §7Utilisez §f/repairall §7pour tout réparer §8(cooldown 24h)§7.\n" +
        SEP,

        // Message 4 : Vision nocturne
        SEP + "\n" +
        PRE + "§eMiner dans le noir ?\n" +
        "  §8| §7Activez la vision nocturne avec §f/vision §7si vous avez la permission.\n" +
        "  §8| §7Évitez la cobblestone inutile avec §f/cobble§7.\n" +
        SEP,

        // Message 5 : Primes (bounty)
        SEP + "\n" +
        PRE + "§6§l\u2620 §eSysteme de primes !\n" +
        "  §8| §7Placez une prime sur un joueur avec §f/prime <joueur> <montant>§7.\n" +
        "  §8| §7Le joueur qui le tue récupère la somme !\n" +
        "  §8| §7Une seule prime par joueur, une seule cible à la fois.\n" +
        SEP
    );

    // ── Logique ───────────────────────────────────────────────────────────────

    private int index = 0;

    public AutoMessageManager(OriginsFightCore plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { sendNext(); }
        }, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    private void sendNext() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return; // personne en ligne
        String msg = MESSAGES.get(index % MESSAGES.size());
        // Chaque \n dans le message est une ligne séparée
        for (String line : msg.split("\n")) {
            Bukkit.broadcastMessage(line);
        }
        index++;
    }
}

