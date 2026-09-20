package fr.redconflict.essentials.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fenêtres ouvertes par le staff sur l'inventaire d'un autre joueur
 * (observateur → observé), de deux natures bien distinctes.
 *
 * <p><b>Miroir</b> ({@code /invsee}) : une fenêtre modifiable, synchronisée dans
 * les deux sens avec le joueur observé — voir {@link InvseeView}. Le staff doit
 * pouvoir retirer un objet dupé ou rendre un objet perdu, pas seulement regarder.
 *
 * <p><b>Instantané</b> ({@code /ec} sur un joueur absent) : une copie relue en
 * base, qui n'appartient à personne. Elle est en <b>lecture seule</b>, et c'est
 * la seule réponse correcte : le joueur peut très bien être en train de jouer sur
 * un autre serveur de la grappe, où sa propre sauvegarde écraserait ce qu'on
 * écrirait ici. Les clics y sont annulés (voir {@code InvseeListener}) — sans
 * quoi les objets qu'on en sort sont créés de rien.
 *
 * <p>Savoir qui regarde qui sert enfin à fermer les fenêtres quand l'observé se
 * déconnecte : une fenêtre ouverte sur un joueur parti manipule un inventaire qui
 * n'appartient plus à personne, ce qui est le chemin classique de la duplication.
 */
public class InvseeSessions {

    /** Période de synchronisation des miroirs, en ticks (250 ms). */
    private static final long SYNC_PERIOD = 5L;

    private final JavaPlugin plugin;

    private final Map<UUID, Session> byViewer = new HashMap<>();

    /**
     * Tâche de synchronisation, créée au premier miroir et arrêtée avec le
     * dernier : {@code /invsee} est une commande rare, elle n'a pas à coûter un
     * réveil toutes les 250 ms en permanence.
     */
    private BukkitTask syncTask;

    public InvseeSessions(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Ouverture ─────────────────────────────────────────────────────────────

    /** Enregistre une fenêtre miroir modifiable ({@code /invsee}). */
    public void openMirror(UUID viewer, InvseeView mirror) {
        byViewer.put(viewer, new Session(mirror.target(), mirror));
        ensureSyncTask();
    }

    /** Enregistre une fenêtre en lecture seule ({@code /ec} hors ligne). */
    public void openSnapshot(UUID viewer, UUID target) {
        byViewer.put(viewer, new Session(target, null));
    }

    // ── Consultation ──────────────────────────────────────────────────────────

    public boolean isViewing(UUID viewer) {
        return byViewer.containsKey(viewer);
    }

    /** Vrai si la fenêtre de cet observateur est une copie en lecture seule. */
    public boolean isReadOnly(UUID viewer) {
        Session session = byViewer.get(viewer);
        return session != null && session.mirror == null;
    }

    /** Le miroir de cet observateur, ou {@code null} (lecture seule, ou rien). */
    public InvseeView mirrorOf(UUID viewer) {
        Session session = byViewer.get(viewer);
        return session == null ? null : session.mirror;
    }

    /** Le joueur observé par cet observateur, ou {@code null}. */
    public UUID targetOf(UUID viewer) {
        Session session = byViewer.get(viewer);
        return session == null ? null : session.target;
    }

    /**
     * Les observateurs actuellement penchés sur cet inventaire.
     *
     * <p>Copie et non vue : l'appelant ferme les fenêtres, ce qui retire des
     * entrées de la table pendant qu'il la parcourt.
     */
    public List<UUID> viewersOf(UUID target) {
        List<UUID> viewers = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : byViewer.entrySet()) {
            if (entry.getValue().target.equals(target)) viewers.add(entry.getKey());
        }
        return viewers;
    }

    // ── Fermeture ─────────────────────────────────────────────────────────────

    /**
     * Retire la session de cet observateur et, s'il s'agissait d'un miroir,
     * <b>transmet une dernière fois ses modifications</b> — sinon le dernier
     * geste du staff serait perdu sans rien dire.
     */
    public void close(UUID viewer) {
        Session session = byViewer.remove(viewer);
        if (session != null && session.mirror != null) {
            session.mirror.apply(Bukkit.getPlayer(session.target));
        }
        stopSyncTaskIfIdle();
    }

    /**
     * L'observé s'en va : on lui transmet ce que le staff vient de modifier
     * pendant qu'il est encore là, puis on condamne les miroirs.
     *
     * <p>L'ordre compte. Écrire après son départ ne servirait à rien — sa
     * sauvegarde est déjà partie — alors que les objets sortis du miroir, eux,
     * seraient bel et bien chez le staff. C'est exactement la duplication que
     * cette classe existe pour éviter.
     */
    public void retireTarget(Player target) {
        for (UUID viewerId : viewersOf(target.getUniqueId())) {
            Session session = byViewer.get(viewerId);
            if (session == null || session.mirror == null) continue;
            session.mirror.apply(target);
            session.mirror.markDead();
        }
    }

    // ── Synchronisation ───────────────────────────────────────────────────────

    private void ensureSyncTask() {
        if (syncTask != null) return;
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sync, SYNC_PERIOD, SYNC_PERIOD);
    }

    private void stopSyncTaskIfIdle() {
        if (syncTask == null) return;
        for (Session session : byViewer.values()) {
            if (session.mirror != null) return;
        }
        syncTask.cancel();
        syncTask = null;
    }

    /**
     * Un passage de synchronisation : on <b>envoie d'abord</b> ce que le staff a
     * modifié, on relit <b>ensuite</b> l'inventaire de l'observé.
     *
     * <p>Cet ordre est le cœur du mécanisme. Relire d'abord écraserait dans le
     * miroir les gestes du staff qu'on n'a pas encore transmis.
     */
    private void sync() {
        for (Session session : new ArrayList<>(byViewer.values())) {
            InvseeView mirror = session.mirror;
            if (mirror == null || mirror.isDead()) continue;

            Player target = Bukkit.getPlayer(session.target);
            if (target == null || !target.isOnline()) continue;

            mirror.apply(target);
            mirror.refresh(target);
        }
        stopSyncTaskIfIdle();
    }

    /** Une fenêtre ouverte : sur qui, et modifiable ou non. */
    private static final class Session {
        private final UUID target;
        /** {@code null} pour un instantané en lecture seule. */
        private final InvseeView mirror;

        private Session(UUID target, InvseeView mirror) {
            this.target = target;
            this.mirror = mirror;
        }
    }
}
