package fr.redconflict.automsg;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.text.ChatFont;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Messages d'information diffusés en rotation dans le chat.
 *
 * <p><b>Les colonnes sont calculées, plus alignées à la main.</b> Les lignes
 * étaient auparavant écrites en dur, la commande complétée d'espaces jusqu'à ce
 * que le tiret « tombe à peu près bien ». Ça ne pouvait pas marcher : la police
 * du chat est proportionnelle, donc {@code /ks} et {@code /profil} n'occupent
 * pas la même place à nombre de caractères égal, et chaque ligne se décalait de
 * quelques pixels — visiblement, sur huit messages. Chaque ligne est désormais
 * décrite par un couple (commande, description), et {@link ChatFont} complète
 * jusqu'à une colonne commune mesurée EN PIXELS sur la commande la plus large de
 * la section. Ajouter une commande plus longue réaligne le bloc tout seul.
 *
 * <p><b>Le trait de séparation est un trait.</b> C'étaient trente-deux tirets,
 * donc une ligne à trous, plus courte que le texte qu'elle encadrait. C'est
 * maintenant un trait plein de la largeur du chat, comme celui de
 * {@code Announce}.
 *
 * <p>Les textes sont volontairement courts : au-delà de la largeur du chat, le
 * client passe à la ligne SANS conserver l'indentation, et la colonne qu'on
 * vient de construire disparaît. Une description tient en trois ou quatre mots.
 */
public class AutoMessageManager {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Intervalle entre deux messages, en ticks (20 ticks = 1 seconde). */
    private static final long INTERVAL_TICKS = 20L * 60 * 30; // 30 minutes

    /** Préfixe de la ligne de titre. */
    private static final String PREFIX = "§8[§6§lRedConflict§8] ";

    /**
     * Marge entre la colonne des commandes et le tiret, en pixels.
     *
     * <p>12 et pas moins : c'est à partir de là que toute largeur se compose
     * exactement en espaces normaux et gras, donc que la colonne tombe au pixel
     * (voir {@link ChatFont#padTo}).
     */
    private static final int COLUMN_GAP = 12;

    /** Trait de séparation, à la largeur de la fenêtre de chat. */
    private static final String SEPARATOR = ChatFont.bar("§8", ChatFont.CHAT_WIDTH);

    /**
     * Les messages, dans leur ordre de passage.
     *
     * <p>Pour en modifier un, il n'y a que du texte à toucher : l'alignement
     * n'est écrit nulle part, il est recalculé à l'affichage.
     */
    private static final List<Message> MESSAGES = Arrays.asList(

            new Message("§6§l★ §eToutes les commandes avec §f/commands"),

            new Message("§c§l⚔ §eSystème de duels",
                    line("/duel <joueur>", "Duel avec votre stuff"),
                    line("/duelk <joueur>", "Duel avec un kit"),
                    line("/duelrandom", "Adversaire au hasard"),
                    line("/duelkrandom", "Au hasard, avec kit")),

            new Message("§e§l★ §eVos statistiques de combat",
                    line("/ks", "Kills, morts, ratio"),
                    line("/profil <joueur>", "Profil d'un joueur"),
                    line("/ct", "Statut de combat")),

            new Message("§6§l$ §eÉconomie du serveur",
                    line("/hdv", "Hôtel des ventes"),
                    line("/shop", "Boutique du serveur"),
                    line("/baltop", "Les plus riches")),

            new Message("§6§l☠ §ePrimes et loto",
                    line("/prime <joueur>", "Mettre une prime"),
                    line("/loto <montant>", "Parier au loto"),
                    line("/loto next", "Prochain tirage")),

            new Message("§a§l♥ §eAmis et échanges",
                    line("/friend add <joueur>", "Ajouter un ami"),
                    line("/friend list", "Votre liste d'amis"),
                    line("/trade <joueur>", "Échange sécurisé"),
                    note("Entre amis, aucun dégât mutuel.")),

            new Message("§d§l✦ §eÉvénements",
                    line("/plannings", "Les prochains events"),
                    note("Tournois, PvP, lotos : restez connectés !")),

            new Message("§7§l★ §eUtilitaires",
                    line("/repairall", "Tout réparer §8(24h)"),
                    line("/cobble", "Filtrer la cobble"),
                    line("/furnace", "Cuire sans four"),
                    line("/bottlexp", "Embouteiller l'XP"))
    );

    // ── Logique ───────────────────────────────────────────────────────────────

    private int index = 0;

    public AutoMessageManager(RedConflictCore plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                sendNext();
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    private void sendNext() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return; // personne pour lire
        }
        for (String line : MESSAGES.get(index % MESSAGES.size()).render()) {
            Bukkit.broadcastMessage(line);
        }
        index++;
    }

    // ── Modèle ────────────────────────────────────────────────────────────────

    /** Une ligne « commande — description ». */
    private static Entry line(String command, String description) {
        return new Entry(command, description);
    }

    /** Une ligne de commentaire, sans commande : elle n'entre pas dans la colonne. */
    private static Entry note(String text) {
        return new Entry(null, text);
    }

    private static final class Entry {
        private final String command;     // null = simple remarque
        private final String description;

        private Entry(String command, String description) {
            this.command = command;
            this.description = description;
        }
    }

    /** Un message : une ligne de titre, puis ses lignes de détail. */
    private static final class Message {
        private final String title;
        private final Entry[] entries;

        private Message(String title, Entry... entries) {
            this.title = title;
            this.entries = entries;
        }

        /**
         * Rend le message, colonne comprise.
         *
         * <p>La colonne est mesurée ici, à chaque diffusion : c'est du calcul
         * négligeable une fois par demi-heure, et ça garantit qu'une commande
         * ajoutée ne laisse pas derrière elle un alignement périmé.
         */
        private List<String> render() {
            int column = 0;
            for (Entry entry : entries) {
                if (entry.command != null) {
                    column = Math.max(column, ChatFont.width(entry.command));
                }
            }
            column += COLUMN_GAP;

            List<String> lines = new ArrayList<String>();
            lines.add(SEPARATOR);
            lines.add(PREFIX + title);
            for (Entry entry : entries) {
                if (entry.command == null) {
                    lines.add("  §8| §7" + entry.description);
                } else {
                    lines.add("  §8| §f" + ChatFont.padTo(entry.command, column)
                            + "§8— §7" + entry.description);
                }
            }
            lines.add(SEPARATOR);
            return lines;
        }
    }
}
