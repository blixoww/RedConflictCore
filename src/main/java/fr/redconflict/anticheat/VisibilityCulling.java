package fr.redconflict.anticheat;

import fr.redconflict.staff.StaffManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-ESP par privation de données : le serveur cesse d'envoyer les joueurs que
 * le spectateur ne peut pas voir.
 *
 * <p><b>C'est la seule famille de défense qui ait un plafond.</b> Un cheat ne
 * peut afficher que ce que le client a REÇU. Détecter un ESP est un jeu du chat
 * et de la souris qu'on finit toujours par perdre ; ne pas envoyer la position
 * de la cible met fin à la partie. Aucune modification du client n'y change quoi
 * que ce soit, parce qu'il n'y a rien à modifier : l'information n'est pas là.
 *
 * <p><b>L'asymétrie qui rend ça jouable.</b> On masque LENTEMENT et on révèle
 * INSTANTANÉMENT. Un joueur qui perd la ligne de vue reste visible pendant
 * {@code hide-delay-ms} ; dès qu'il réapparaît, il est rendu au tick suivant.
 * Conséquence : un joueur honnête ne perd jamais une cible qu'il regarde — un
 * adversaire qui sort d'un angle est déjà visible avant d'être à l'écran. Un
 * utilisateur d'ESP, lui, perd tout ce qu'il ne peut pas voir au bout d'une
 * seconde et demie, c'est-à-dire tout ce qui faisait l'intérêt de son ESP.
 *
 * <p><b>Ce que ça ne remplace pas.</b> Spigot cesse déjà d'envoyer les joueurs
 * au-delà de {@code entity-tracking-range.players} (48 blocs par défaut) : un
 * radar longue portée ne voit donc déjà rien. Le gain est ICI, à l'intérieur de
 * cette portée, derrière les murs et sous terre — là où l'ESP est réellement
 * décisif en faction.
 *
 * <p><b>Coût.</b> Le tracé de ligne de vue n'est fait que pour les paires assez
 * proches pour que ça compte, et un plafond par passe borne le travail. Au-delà
 * du plafond, les paires restantes sont traitées à la passe suivante : le
 * masquage prend un peu plus de temps, le serveur ne rame jamais.
 */
public class VisibilityCulling {

    private final Plugin plugin;
    private BukkitTask task;

    /** Depuis quand une paire (spectateur, cible) est sans ligne de vue. */
    private final Map<UUID, Map<UUID, Long>> blindSince = new HashMap<UUID, Map<UUID, Long>>();

    /** Index de reprise du parcours, pour répartir le travail entre les passes. */
    private int cursor;

    public VisibilityCulling(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("anticheat.visibility.enabled", false)) {
            plugin.getLogger().info("[AC] Masquage anti-ESP désactivé "
                    + "(anticheat.visibility.enabled: false).");
            return;
        }
        long interval = Math.max(1, plugin.getConfig().getInt("anticheat.visibility.interval-ticks", 4));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, interval, interval);
        plugin.getLogger().info("[AC] Masquage anti-ESP actif (passe toutes les "
                + interval + " ticks).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Tout rendre visible : sinon un /reload laisserait des joueurs masqués
        // sans plus personne pour les révéler.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer != target && !isVanished(target)) {
                    viewer.showPlayer(target);
                }
            }
        }
        blindSince.clear();
    }

    // ── Passe ──────────────────────────────────────────────────────────────────

    private void sweep() {
        List<Player> online = new ArrayList<Player>(Bukkit.getOnlinePlayers());
        if (online.size() < 2) {
            return;
        }
        final double losRadius = plugin.getConfig().getDouble("anticheat.visibility.los-radius", 40.0);
        final double alwaysVisible = plugin.getConfig().getDouble("anticheat.visibility.always-visible-radius", 6.0);
        final long hideDelay = plugin.getConfig().getLong("anticheat.visibility.hide-delay-ms", 1500L);
        final int budget = plugin.getConfig().getInt("anticheat.visibility.max-traces-per-pass", 2000);

        final double losSq = losRadius * losRadius;
        final double closeSq = alwaysVisible * alwaysVisible;
        final long now = System.currentTimeMillis();

        int traces = 0;
        int count = online.size();
        // Reprise là où la passe précédente s'est arrêtée : sur un serveur
        // chargé, aucun joueur n'est systématiquement le dernier servi.
        for (int step = 0; step < count; step++) {
            Player viewer = online.get((cursor + step) % count);
            if (!viewer.isOnline()) {
                continue;
            }
            // Le staff voit tout : c'est son travail.
            if (viewer.hasPermission("redconflict.anticheat.seeall")
                    || viewer.hasPermission("staff.staff")) {
                revealAll(viewer, online);
                continue;
            }
            World world = viewer.getWorld();
            Location eye = viewer.getEyeLocation();

            for (Player target : online) {
                if (target == viewer || !target.isOnline() || target.getWorld() != world) {
                    continue;
                }
                // Le vanish appartient au StaffManager : on ne le contredit jamais.
                if (isVanished(target)) {
                    continue;
                }
                double distSq = eye.distanceSquared(target.getLocation());

                // Trop proche pour qu'on se pose la question : un adversaire au
                // corps à corps doit être visible, mur ou pas.
                if (distSq <= closeSq) {
                    reveal(viewer, target);
                    continue;
                }
                // Hors du rayon d'intérêt : Spigot s'en occupe déjà, et tracer
                // une ligne de 60 blocs pour rien coûte cher.
                if (distSq > losSq) {
                    reveal(viewer, target);
                    continue;
                }
                if (traces >= budget) {
                    break; // le reste passera à la passe suivante
                }
                traces++;

                if (hasLineOfSight(eye, target)) {
                    forget(viewer, target);
                    reveal(viewer, target);
                } else if (blindFor(viewer, target, now) >= hideDelay) {
                    conceal(viewer, target);
                }
            }
            if (traces >= budget) {
                cursor = (cursor + step) % count; // reprendre ici
                return;
            }
        }
        cursor = 0;
    }

    /**
     * Ligne de vue œil → cible, en visant à la fois les pieds et la tête.
     *
     * <p>Deux tracés et non un : un joueur derrière un muret bas a la tête
     * dégagée et les pieds cachés. Le considérer invisible parce qu'un des deux
     * points est bloqué le ferait disparaître alors qu'on le voit.
     */
    private boolean hasLineOfSight(Location eye, Player target) {
        Location feet = target.getLocation();
        return traceClear(eye, feet.clone().add(0, 0.2, 0))
                || traceClear(eye, feet.clone().add(0, 1.7, 0));
    }

    private boolean traceClear(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length < 0.5) {
            return true;
        }
        try {
            BlockIterator iterator = new BlockIterator(
                    from.getWorld(), from.toVector(), direction.normalize(), 0, (int) length);
            while (iterator.hasNext()) {
                Block block = iterator.next();
                if (block.getType().isOccluding()) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException e) {
            // Vecteur dégénéré ou chunk non chargé : dans le doute, on montre.
            return true;
        }
    }

    // ── État ───────────────────────────────────────────────────────────────────

    /** Durée, en ms, pendant laquelle la cible est restée hors de vue. */
    private long blindFor(Player viewer, Player target, long now) {
        Map<UUID, Long> byTarget = blindSince.computeIfAbsent(
                viewer.getUniqueId(), id -> new HashMap<UUID, Long>());
        Long since = byTarget.get(target.getUniqueId());
        if (since == null) {
            byTarget.put(target.getUniqueId(), now);
            return 0;
        }
        return now - since;
    }

    private void forget(Player viewer, Player target) {
        Map<UUID, Long> byTarget = blindSince.get(viewer.getUniqueId());
        if (byTarget != null) {
            byTarget.remove(target.getUniqueId());
        }
    }

    private void reveal(Player viewer, Player target) {
        if (!viewer.canSee(target)) {
            viewer.showPlayer(target);
        }
    }

    private void conceal(Player viewer, Player target) {
        if (viewer.canSee(target)) {
            viewer.hidePlayer(target);
        }
    }

    private void revealAll(Player viewer, List<Player> online) {
        for (Player target : online) {
            if (target != viewer && !isVanished(target)) {
                reveal(viewer, target);
            }
        }
    }

    private static boolean isVanished(Player player) {
        try {
            return StaffManager.get().isVanished(player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** À la déconnexion : ne pas garder d'état pour un joueur parti. */
    public void forget(UUID player) {
        blindSince.remove(player);
        for (Map<UUID, Long> byTarget : blindSince.values()) {
            byTarget.remove(player);
        }
    }
}