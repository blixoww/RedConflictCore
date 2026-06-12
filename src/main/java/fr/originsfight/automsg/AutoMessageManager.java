package fr.originsfight.automsg;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.List;

/**
 * Système de messages automatiques dans le chat.
 * <p>
 * Les messages s'affichent à intervalle régulier (configurable via INTERVAL_TICKS).
 * Modifier la liste MESSAGES pour changer les annonces.
 * <p>
 * Intervalle par défaut : 5 minutes (6000 ticks).
 */
public class AutoMessageManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Intervalle entre chaque message en ticks (20 ticks = 1 seconde).
     */
    private static final long INTERVAL_TICKS = 20L * 60 * 30; // 30 minutes

    /**
     * Séparateur affiché avant/après le message.
     */
    private static final String SEP = "§8--------------------------------";

    /**
     * Préfixe du message automatique.
     */
    private static final String PRE = "§8[§6§lRedConflict§8] ";

    /**
     * Liste des messages affichés en rotation. Modifiez-les librement.
     */
    private static final List<String> MESSAGES = Arrays.asList(

            // 1 — Présentation générale
            SEP + "\n" +
                    PRE + "§6§l★ Tapez §f/commands §epour voir tout ce que vous pouvez faire !\n" +
                    SEP,

            // 2 — Duels
            SEP + "\n" +
                    PRE + "§c§l⚔ §eSystème de §fDuels §edisponible !\n" +
                    "  §8| §f/duel <joueur>        §7— Duel avec votre équipement\n" +
                    "  §8| §f/duelk <joueur>       §7— Duel avec un kit défini\n" +
                    "  §8| §f/duelrandom           §7— Duel aléatoire avec votre stuff\n" +
                    "  §8| §f/duelkrandom          §7— Duel aléatoire avec kit\n" +
                    SEP,

            // 3 — Profil & statistiques
            SEP + "\n" +
                    PRE + "§e§lVos stats de combat sont accessibles en temps réel !\n" +
                    "  §8| §f/ks              ��7— Vos kills, morts, ratio et temps de jeu\n" +
                    "  §8| §f/profil <joueur> §7— Voir le profil complet d'un joueur\n" +
                    "  §8| §f/ct              §7— Vérifier votre statut de combat\n" +
                    SEP,

            // 4 — Économie
            SEP + "\n" +
                    PRE + "§6§l$ §eÉconomie du serveur !\n" +
                    "  §8| §f/hdv             §7— Hôtel des Ventes : achat/vente entre joueurs\n" +
                    "  §8| §f/shop            §7— Boutique du serveur\n" +
                    "  §8| §f/baltop          §7— Classement des plus riches\n" +
                    SEP,

            // 5 — Primes & Loto
            SEP + "\n" +
                    PRE + "§6§l☠ §ePrimes & §6§l★ §eLoto !\n" +
                    "  §8| §f/prime <joueur> <montant> §7— Mettre une prime sur un joueur\n" +
                    "  §8| §f/loto <montant>            §7— Parier pendant le loto\n" +
                    "  §8| §f/loto next                 §7— Savoir quand arrive le prochain loto\n" +
                    SEP,

            // 6 — Amis & Trade
            SEP + "\n" +
                    PRE + "§a§l♥ §eAmis & Échanges !\n" +
                    "  §8| §f/friend add <joueur>  §7— Ajouter un ami §8(pas de dégâts mutuels)\n" +
                    "  §8| §f/friend list          §7— Voir votre liste d'amis\n" +
                    "  §8| §f/trade <joueur>       §7— Échange sécurisé d'items\n" +
                    SEP,

            // 7 — Événements & Plannings
            SEP + "\n" +
                    PRE + "§d§l✦ §eÉvénements & Plannings !\n" +
                    "  §8| §f/plannings  §7— Voir les prochains événements prévus sur le serveur\n" +
                    "  §8| §7Tournois, events PvP, lotos spéciaux — restez connectés !\n" +
                    SEP,

            // 8 — Utilitaires
            SEP + "\n" +
                    PRE + "§7§lUtilitaires pratiques !\n" +
                    "  §8| §f/repairall  §7— Réparer tout votre équipement §8(cooldown 24h)\n" +
                    "  §8| §f/cobble     §7— Filtrer la cobblestone automatiquement\n" +
                    "  §8| §f/furnace    §7— Cuire sans four §8(§f/furnace this §8/ §f/furnace all§8)\n" +
                    "  §8| §f/bottlexp   §7— Embouteiller vos niveaux d'XP\n" +
                    SEP
    );

    // ── Logique ───────────────────────────────────────────────────────────────

    private int index = 0;

    public AutoMessageManager(OriginsFightCore plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() {
                sendNext();
            }
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

