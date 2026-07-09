package fr.originsfight.friend;

import fr.originsfight.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Gestionnaire du système d'amis.
 *
 * Règles :
 *  - Maximum 5 amis par joueur.
 *  - Relation bidirectionnelle : si A et B sont amis, ils ne se font pas de dégâts.
 *  - Demande nécessaire : A envoie une demande, B doit l'accepter.
 *  - Persistance dans la base H2 centrale (voir FriendDatabase).
 *  - Cache mémoire pour les demandes en attente (fiabilité immédiate).
 */
public class FriendManager {

    public static final int MAX_FRIENDS = 5;

    private static FriendManager instance;
    private FriendDatabase database;

    /**
     * Cache mémoire des demandes en attente.
     * Clé externe : UUID du receiver (celui qui reçoit la demande).
     * Clé interne : UUID du sender → nom du sender.
     */
    private final Map<UUID, Map<UUID, String>> pendingCache = new HashMap<>();

    public static FriendManager getInstance() { return instance; }

    public FriendManager() { instance = this; }

    // ── Initialisation ────────────────────────────────────────────────────────

    public boolean enable(RedConflictCore plugin) {
        database = new FriendDatabase(plugin.getCoreDatabase());
        if (!database.init()) return false;
        // Charger les demandes existantes depuis la BDD dans le cache
        loadRequestsCache();
        return true;
    }

    /** Charge toutes les demandes en attente de la BDD dans le cache mémoire. */
    private void loadRequestsCache() {
        Map<UUID, Map<UUID, String>> all = database.getAllPendingRequests();
        pendingCache.clear();
        pendingCache.putAll(all);
    }

    public void disable() {
        if (database != null) database.close();
    }

    // ── Amis ─────────────────────────────────────────────────────────────────

    public boolean areFriends(UUID a, UUID b) {
        return database.areFriends(a, b);
    }

    public List<UUID> getFriends(UUID uuid) {
        return database.getFriends(uuid);
    }

    public int getFriendCount(UUID uuid) {
        return database.countFriends(uuid);
    }

    /** Ajoute la relation amicale (bidirectionnelle) et retire la demande. */
    public void addFriend(UUID a, String nameA, UUID b, String nameB) {
        database.saveName(a, nameA);
        database.saveName(b, nameB);
        database.addFriend(a, b);
        database.removeRequest(a, b);
        database.removeRequest(b, a);
        // Nettoyer le cache
        removeCachedRequest(a, b);
        removeCachedRequest(b, a);
    }

    public void removeFriend(UUID a, UUID b) {
        database.removeFriend(a, b);
    }

    // ── Demandes ─────────────────────────────────────────────────────────────

    public void sendRequest(UUID sender, String senderName, UUID receiver, String receiverName) {
        database.addRequest(sender, senderName, receiver, receiverName);
        // Mettre à jour le cache immédiatement
        pendingCache.computeIfAbsent(receiver, k -> new LinkedHashMap<>()).put(sender, senderName);
    }

    public void denyRequest(UUID sender, UUID receiver) {
        database.removeRequest(sender, receiver);
        // Nettoyer le cache
        removeCachedRequest(sender, receiver);
    }

    public boolean hasRequest(UUID sender, UUID receiver) {
        // Vérifier d'abord le cache mémoire
        Map<UUID, String> requests = pendingCache.get(receiver);
        if (requests != null && requests.containsKey(sender)) return true;
        // Fallback sur la BDD
        return database.hasRequest(sender, receiver);
    }

    /** Demandes reçues par receiver (sender_uuid → sender_name). */
    public Map<UUID, String> getPendingRequests(UUID receiver) {
        // Retourner depuis le cache mémoire (toujours à jour)
        Map<UUID, String> cached = pendingCache.get(receiver);
        if (cached != null && !cached.isEmpty()) {
            return new LinkedHashMap<>(cached);
        }
        // Fallback sur la BDD si le cache est vide
        Map<UUID, String> fromDb = database.getPendingRequests(receiver);
        if (!fromDb.isEmpty()) {
            pendingCache.put(receiver, new LinkedHashMap<>(fromDb));
        }
        return fromDb;
    }

    // ── Utilitaire cache ──────────────────────────────────────────────────────

    private void removeCachedRequest(UUID sender, UUID receiver) {
        Map<UUID, String> requests = pendingCache.get(receiver);
        if (requests != null) {
            requests.remove(sender);
            if (requests.isEmpty()) pendingCache.remove(receiver);
        }
    }

    // ── Noms ─────────────────────────────────────────────────────────────────

    public void saveName(UUID uuid, String name) {
        database.saveName(uuid, name);
    }

    public String getName(UUID uuid) {
        // D'abord en ligne
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        // Sinon DB
        return database.getName(uuid);
    }
}

