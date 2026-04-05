package fr.originsfight.loto;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Gestionnaire du système de Loto automatique.
 *
 * Cycle :
 *   1. Attente aléatoire (2-4h, premier loto dans 5min-1h)
 *   2. Phase de paris : 2 minutes
 *      - Rappels à 1 minute, 30 secondes, 10 secondes
 *   3. Phase finale : désigne un vainqueur pondéré par la mise
 *      - Minimum 3 participants, sinon annulé + remboursement
 *      - Multiplicateur (1.0 → 2.0) selon le nombre de joueurs
 */
public class LotoManager {

    private static LotoManager instance;

    /** Durée d'un loto ouvert en ticks (2 minutes = 2400 ticks). */
    private static final long LOTO_DURATION_TICKS = 20L * 60 * 2;

    /** Délai minimum entre deux lotos (1h en ticks). */
    private static final long MIN_INTERVAL_TICKS = 20L * 60 * 60;
    /** Délai maximum entre deux lotos (2h en ticks). */
    private static final long MAX_INTERVAL_TICKS = 20L * 60 * 60 * 2;

    /** Délai max pour le premier loto après démarrage du serveur (1h en ticks). */
    private static final long FIRST_MAX_TICKS = 20L * 60 * 60;

    /** Minimum de participants pour valider le loto. */
    private static final int MIN_PARTICIPANTS = 3;

    private final OriginsFightCore plugin;
    private final Random random = new Random();

    /** Mises des joueurs pour le loto en cours. Clé = UUID, Valeur = montant. */
    private final Map<UUID, Long> bets = new LinkedHashMap<>();
    /** Noms des joueurs pour l'affichage. */
    private final Map<UUID, String> betNames = new HashMap<>();

    /** true si un loto est actuellement ouvert aux paris. */
    private boolean open = false;

    /** Tâche de fin du loto en cours. */
    private BukkitTask endTask;
    /** Tâches de rappel (pour pouvoir les annuler lors d'un forceStop). */
    private final List<BukkitTask> reminderTasks = new ArrayList<>();

    /** Timestamp (ms) auquel le loto en cours a démarré. */
    private long lotoStartTime = 0;
    /** Timestamp (ms) prévu pour le prochain loto (0 si inconnu). */
    private long nextLotoTime = 0;
    /** Tâche du prochain loto programmé. */
    private BukkitTask nextScheduledTask;

    public static LotoManager getInstance() { return instance; }

    public LotoManager(OriginsFightCore plugin) {
        this.plugin = plugin;
        instance = this;
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Lance le scheduler : premier loto dans [0-1h], puis toutes les [1-2h]. */
    public void startScheduler() {
        long firstDelay = randomTicks(1L, FIRST_MAX_TICKS);
        scheduleNext(firstDelay);
    }

    private void scheduleNext(long delayTicks) {
        nextLotoTime = System.currentTimeMillis() + (delayTicks * 50L); // 1 tick = 50ms
        nextScheduledTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                nextLotoTime = 0;
                startLoto();
            }
        }, delayTicks);
    }

    private long randomTicks(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    // ── Loto lifecycle ───────────────────────────────────────────────────────

    /** Démarre un nouveau loto (automatique ou manuel). */
    private void startLoto() {
        if (open) return;

        if (Bukkit.getOnlinePlayers().size() < MIN_PARTICIPANTS) {
            // Pas assez de joueurs en ligne, reprogrammer
            scheduleNext(randomTicks(MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS));
            return;
        }

        open = true;
        lotoStartTime = System.currentTimeMillis();
        bets.clear();
        betNames.clear();
        reminderTasks.clear();

        // Annonce globale
        broadcast(RC.LOTO_START);

        // Tâche de fin dans 2 minutes
        endTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { endLoto(); }
        }, LOTO_DURATION_TICKS);

        // Rappel à 1 minute restante (= 1 min après le début)
        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "1 minute"));
            }
        }, 20L * 60));

        // Rappel à 30 secondes restantes (= 1min30 après le début)
        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "30 secondes"));
            }
        }, 20L * 90));

        // Rappel à 10 secondes restantes (= 1min50 après le début)
        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "10 secondes"));
            }
        }, 20L * 110));
    }

    /** Termine le loto en cours : tire le vainqueur ou annule. */
    private void endLoto() {
        if (!open) return;
        open = false;
        lotoStartTime = 0;

        if (bets.size() < MIN_PARTICIPANTS) {
            // Annulation : rembourser tout le monde
            Economy eco = plugin.getEconomy();
            if (eco != null) {
                for (Map.Entry<UUID, Long> entry : bets.entrySet()) {
                    // Utiliser OfflinePlayer pour rembourser même les joueurs déconnectés
                    eco.depositPlayer(Bukkit.getOfflinePlayer(entry.getKey()), entry.getValue());
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) {
                        p.sendMessage(RC.fmt(RC.LOTO_REFUND, entry.getValue()));
                    }
                }
            }
            broadcast(RC.fmt(RC.LOTO_CANCELLED, bets.size(), MIN_PARTICIPANTS));
        } else {
            // Calcul du multiplicateur (1.0 à 2.0) basé sur le nombre de joueurs
            double multiplier = 1.0 + Math.min(1.0, (bets.size() - MIN_PARTICIPANTS) / 10.0);

            // Cagnotte totale
            long totalPool = 0;
            for (long amount : bets.values()) {
                totalPool += amount;
            }
            long finalPool = (long) (totalPool * multiplier);

            // Tirage au sort pondéré par la mise
            UUID winnerId = drawWeightedWinner();
            String winnerName = betNames.get(winnerId);

            // Verser au vainqueur (même s'il s'est déconnecté)
            Economy eco = plugin.getEconomy();
            if (eco != null) {
                eco.depositPlayer(Bukkit.getOfflinePlayer(winnerId), finalPool);
                Player winner = Bukkit.getPlayer(winnerId);
                if (winner != null) {
                    winner.sendMessage(RC.fmt(RC.LOTO_WIN_PERSONAL, finalPool));
                }
            }

            // Annonce globale
            broadcast(RC.fmt(RC.LOTO_WIN_BROADCAST, winnerName, finalPool,
                    bets.size(), String.format("%.1fx", multiplier)));
        }

        bets.clear();
        betNames.clear();
        reminderTasks.clear();

        // Programmer le prochain loto
        scheduleNext(randomTicks(MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS));
    }

    /** Tirage au sort pondéré par la mise. */
    private UUID drawWeightedWinner() {
        long totalWeight = 0;
        for (long amount : bets.values()) {
            totalWeight += amount;
        }
        long roll = (long) (random.nextDouble() * totalWeight);
        long cumul = 0;
        for (Map.Entry<UUID, Long> entry : bets.entrySet()) {
            cumul += entry.getValue();
            if (roll < cumul) return entry.getKey();
        }
        // Fallback (ne devrait pas arriver)
        return bets.keySet().iterator().next();
    }

    // ── API publique ─────────────────────────────────────────────────────────

    /** Un joueur place un pari. */
    public void placeBet(Player player, long amount) {
        if (!open) {
            player.sendMessage(RC.LOTO_NOT_OPEN);
            return;
        }
        if (bets.containsKey(player.getUniqueId())) {
            player.sendMessage(RC.LOTO_ALREADY_BET);
            return;
        }
        if (amount <= 0) {
            player.sendMessage(RC.LOTO_INVALID_AMOUNT);
            return;
        }

        Economy eco = plugin.getEconomy();
        if (eco == null) {
            player.sendMessage(RC.LOTO_ECO_ERROR);
            return;
        }
        if ((long) eco.getBalance(player) < amount) {
            player.sendMessage(RC.LOTO_NO_MONEY);
            return;
        }

        eco.withdrawPlayer(player, amount);
        bets.put(player.getUniqueId(), amount);
        betNames.put(player.getUniqueId(), player.getName());

        player.sendMessage(RC.fmt(RC.LOTO_BET_OK, amount));
        broadcast(RC.fmt(RC.LOTO_BET_BROADCAST, player.getName(), bets.size()));
    }

    /** Force le démarrage d'un loto (commande staff). Ignore le minimum de joueurs en ligne. */
    public boolean forceStart() {
        if (open) return false;
        // Annuler le prochain loto programmé s'il y en a un
        if (nextScheduledTask != null) {
            nextScheduledTask.cancel();
            nextScheduledTask = null;
        }
        nextLotoTime = 0;

        // Démarrage direct sans vérification du nombre de joueurs
        open = true;
        lotoStartTime = System.currentTimeMillis();
        bets.clear();
        betNames.clear();
        reminderTasks.clear();

        broadcast(RC.LOTO_START);

        endTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { endLoto(); }
        }, LOTO_DURATION_TICKS);

        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "1 minute"));
            }
        }, 20L * 60));

        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "30 secondes"));
            }
        }, 20L * 90));

        reminderTasks.add(Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (open) broadcast(RC.fmt(RC.LOTO_REMINDER, "10 secondes"));
            }
        }, 20L * 110));

        return true;
    }

    /** Force l'arrêt du loto en cours (commande staff). Rembourse tout le monde. */
    public boolean forceStop() {
        if (!open) return false;
        if (endTask != null) endTask.cancel();
        for (BukkitTask task : reminderTasks) {
            if (task != null) task.cancel();
        }
        // Rembourser tout le monde
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            for (Map.Entry<UUID, Long> entry : bets.entrySet()) {
                // Utiliser OfflinePlayer pour rembourser même les joueurs déconnectés
                eco.depositPlayer(Bukkit.getOfflinePlayer(entry.getKey()), entry.getValue());
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null) {
                    p.sendMessage(RC.fmt(RC.LOTO_REFUND, entry.getValue()));
                }
            }
        }
        open = false;
        lotoStartTime = 0;
        bets.clear();
        betNames.clear();
        reminderTasks.clear();
        broadcast(RC.LOTO_FORCE_STOP);

        // Reprogrammer le prochain loto
        scheduleNext(randomTicks(MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS));
        return true;
    }

    public boolean isOpen() { return open; }

    public int getParticipantCount() { return bets.size(); }

    public long getTotalPool() {
        long total = 0;
        for (long a : bets.values()) total += a;
        return total;
    }

    /**
     * Retourne le nombre de secondes restantes avant la fin du loto en cours,
     * ou -1 si aucun loto n'est ouvert.
     */
    public long getRemainingSeconds() {
        if (!open || lotoStartTime == 0) return -1;
        long elapsed = System.currentTimeMillis() - lotoStartTime;
        long durationMs = (LOTO_DURATION_TICKS * 50L); // ticks → ms
        long remaining = (durationMs - elapsed) / 1000L;
        return Math.max(0, remaining);
    }

    /**
     * Retourne le nombre de secondes avant le prochain loto,
     * ou -1 si aucun n'est programmé ou si un loto est en cours.
     */
    public long getSecondsUntilNext() {
        if (open) return -1;
        if (nextLotoTime <= 0) return -1;
        long remaining = (nextLotoTime - System.currentTimeMillis()) / 1000L;
        return Math.max(0, remaining);
    }

    /** Formate un nombre de secondes en "Xh Xmin Xs". */
    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) return "0s";
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("min ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }

    // ── Utilitaire ───────────────────────────────────────────────────────────

    private void broadcast(String msg) {
        for (String line : msg.split("\n")) {
            Bukkit.broadcastMessage(line);
        }
    }
}
