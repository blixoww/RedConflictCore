package fr.redconflict.vote;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.data.PlayerDataServerHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/**
 * Récompenses de vote.
 *
 * <p>Azuriom valide le vote et appelle une seule commande, {@code rcvote
 * <pseudo>}. Tout le reste — montant en PB, table de butin, garantie de
 * fidélité — vit dans {@code vote/recompenses.yml}, à un seul endroit.
 *
 * <p>Passer par le Core n'est pas un détour gratuit : un {@code give} envoyé à
 * la console ne connaît pas les items propres au serveur et livrerait du vide.
 * Ici, c'est le distributeur de la boutique qui remet les objets.
 *
 * <p>Le module pousse aussi l'état du vote vers le HUD du client
 * ({@link VoteStatusMirror}) : l'encart n'apparaît que quand un vote est
 * réellement ouvert, comme celui du site.
 */
public final class VoteModule implements Module, Listener {

    private final RedConflictCore plugin;

    private VoteStorage storage;
    private VoteRewards rewards;
    private VoteStatusMirror statuts;
    private BukkitTask statusTask;

    /**
     * Dernier état envoyé à chaque joueur : {@code {disponibles, prochainVote}}.
     *
     * <p>On mémorise la <b>date limite</b> et non la durée envoyée : la seconde
     * change à chaque passage, la première ne bouge qu'à un vote. Sans ça, on
     * renverrait un paquet toutes les minutes pour rien.
     */
    private final Map<UUID, long[]> dernierStatut = new HashMap<UUID, long[]>();

    public VoteModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Vote";
    }

    @Override
    public void enable() {
        this.storage = new VoteStorage(plugin, plugin.getCoreDatabase());
        if (storage.isAvailable()) {
            storage.ensureTables();
        } else {
            plugin.getLogger().warning("[Vote] Base H2 indisponible : ni compteur, ni file d'attente.");
        }

        this.rewards = new VoteRewards(plugin, storage);
        rewards.reload();

        new CommandRegistrar(plugin).register("rcvote", new VoteCommand(plugin, rewards, storage));
        Bukkit.getPluginManager().registerEvents(this, plugin);

        this.statuts = new VoteStatusMirror(plugin, plugin.getSiteDatabase());
        demarrerStatut();
    }

    @Override
    public void disable() {
        if (statusTask != null) {
            try { statusTask.cancel(); } catch (Exception ignored) { }
            statusTask = null;
        }
    }

    public VoteRewards getRewards() {
        return rewards;
    }

    /** Le compteur de votes. {@code null} tant que le module n'est pas actif. */
    public VoteStorage getStorage() {
        return storage;
    }

    /**
     * Remet ce qui attendait le joueur.
     *
     * <p>Deux secondes de retard : à la connexion, l'inventaire n'est pas encore
     * restauré par le module de synchronisation, et un objet donné trop tôt
     * serait écrasé par le chargement qui suit.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        final Player joueur = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!joueur.isOnline() || rewards == null) return;
                int remis = rewards.remettreEnAttente(joueur);
                if (remis > 0) {
                    plugin.getLogger().info("[Vote] " + remis + " lot(s) remis à " + joueur.getName() + ".");
                }
            }
        }, 40L);

        // L'encart doit être juste dès l'arrivée, sans attendre le tour suivant.
        rafraichirStatut(joueur, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        dernierStatut.remove(event.getPlayer().getUniqueId());
    }

    // ── État du vote poussé au client ─────────────────────────────────────────

    /**
     * Renvoie l'état du vote d'un joueur au client, après un délai.
     *
     * <p>Appelée à la connexion et juste après un {@code rcvote} : dans les deux
     * cas l'encart doit changer tout de suite, pas au prochain tour. Le délai
     * laisse au site le temps d'avoir recalculé sa ligne — AzLink relève les
     * commandes après coup, le joueur a déjà rechargé sa page de vote.
     *
     * @param delaiTicks attente avant la lecture, en ticks
     */
    public void rafraichirStatut(final Player joueur, long delaiTicks) {
        if (joueur == null || statuts == null || !statuts.isAvailable()) return;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!joueur.isOnline()) return;
                List<UUID> un = new ArrayList<UUID>(1);
                un.add(joueur.getUniqueId());
                lireEtEnvoyer(un);
            }
        }, Math.max(1L, delaiTicks));
    }

    /** Variante par pseudo, pour les appels venus d'une commande console. */
    public void rafraichirStatut(String pseudo, long delaiTicks) {
        Player joueur = Bukkit.getPlayerExact(pseudo);
        if (joueur != null) rafraichirStatut(joueur, delaiTicks);
    }

    /**
     * Relit périodiquement l'état de tous les connectés.
     *
     * <p>Peu fréquent, et c'est voulu : ce qu'on transmet est une date limite,
     * que le client compare lui-même à l'heure courante. Le passage ne sert donc
     * qu'à rattraper un vote enregistré ailleurs — pas à faire vivre un compte à
     * rebours.
     */
    private void demarrerStatut() {
        if (statuts == null || !statuts.isAvailable()) {
            plugin.getLogger().info("[Vote] Pont site inactif : l'encart de vote restera masqué en jeu.");
            return;
        }
        long secondes = Math.max(15L, plugin.getConfig().getLong("vote.status-refresh-seconds", 60L));
        long periode = 20L * secondes;

        // Le tour commence sur le thread principal : la liste des connectés s'y
        // lit sans risque, la requête part ensuite en asynchrone.
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                Collection<? extends Player> connectes = Bukkit.getOnlinePlayers();
                if (connectes.isEmpty()) {
                    dernierStatut.clear();
                    return;
                }
                List<UUID> uuids = new ArrayList<UUID>(connectes.size());
                for (Player p : connectes) uuids.add(p.getUniqueId());
                dernierStatut.keySet().retainAll(uuids);
                lireEtEnvoyer(uuids);
            }
        }, 20L * 10L, periode);
    }

    /** Lecture en asynchrone, envoi sur le thread principal. */
    private void lireEtEnvoyer(final List<UUID> uuids) {
        if (statuts == null || !statuts.isAvailable() || uuids.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final Map<UUID, VoteStatusMirror.Statut> lus = statuts.lire(uuids);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() { envoyer(uuids, lus); }
                });
            }
        });
    }

    private void envoyer(List<UUID> uuids, Map<UUID, VoteStatusMirror.Statut> lus) {
        long maintenant = System.currentTimeMillis() / 1000L;

        for (UUID uuid : uuids) {
            Player joueur = Bukkit.getPlayer(uuid);
            if (joueur == null || !joueur.isOnline()) continue;

            VoteStatusMirror.Statut statut = lus.get(uuid);
            if (statut == null) statut = VoteStatusMirror.Statut.inconnu();

            int disponibles = Math.max(0, statut.disponibles);
            int secondes = 0;
            if (disponibles == 0 && statut.prochainVote > 0) {
                long reste = statut.prochainVote - maintenant;
                if (reste <= 0) {
                    // L'échéance est passée : c'est votable, même si le site n'a
                    // pas encore réécrit sa ligne (il ne le fera qu'à la
                    // prochaine page chargée par le joueur).
                    disponibles = 1;
                } else {
                    secondes = (int) Math.min(reste, Integer.MAX_VALUE);
                }
            }

            long[] precedent = dernierStatut.get(uuid);
            if (precedent != null
                    && precedent[0] == disponibles
                    && precedent[1] == statut.prochainVote) {
                continue;
            }
            dernierStatut.put(uuid, new long[] { disponibles, statut.prochainVote });

            try {
                PlayerDataServerHandler.sendVoteStatus(joueur, disponibles, secondes);
            } catch (Exception ignored) { }
        }
    }
}
