package fr.originsfight.trade;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gestionnaire centralisé des sessions de trade.
 * Gère aussi les invitations en attente.
 */
public class TradeManager {

    private static TradeManager instance;

    // UUID du joueur → session active (en tant que A ou B)
    private final Map<UUID, TradeSession> activeSessions = new HashMap<>();

    // UUID du joueur invitant → UUID du joueur invité (invitation en attente)
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public static TradeManager getInstance() {
        if (instance == null) instance = new TradeManager();
        return instance;
    }

    /**
     * Envoie une invitation de trade de sender vers target.
     * Retourne false si l'un ou l'autre est déjà en trade.
     */
    public boolean invite(Player sender, Player target) {
        if (isInTrade(sender) || isInTrade(target)) return false;
        pendingInvites.put(sender.getUniqueId(), target.getUniqueId());
        return true;
    }

    /**
     * Accepte une invitation : crée la session et ouvre l'inventaire aux deux joueurs.
     * Le joueur "target" accepte l'invitation de "inviter".
     */
    public TradeSession acceptInvite(Player inviter, Player target) {
        pendingInvites.remove(inviter.getUniqueId());
        TradeSession session = new TradeSession(inviter, target);
        activeSessions.put(inviter.getUniqueId(), session);
        activeSessions.put(target.getUniqueId(), session);
        inviter.openInventory(session.getInventory());
        target.openInventory(session.getInventory());
        return session;
    }

    /**
     * Refuse ou annule une invitation.
     */
    public void declineInvite(Player inviter) {
        pendingInvites.remove(inviter.getUniqueId());
    }

    /**
     * Retourne la session active d'un joueur, ou null.
     */
    public TradeSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    /**
     * Retourne l'UUID du joueur que 'sender' a invité, ou null.
     */
    public UUID getPendingTarget(Player sender) {
        return pendingInvites.get(sender.getUniqueId());
    }

    /**
     * Retourne l'UUID de l'inviteur qui a invité 'target', ou null.
     */
    public UUID getPendingInviter(Player target) {
        for (Map.Entry<UUID, UUID> e : pendingInvites.entrySet()) {
            if (e.getValue().equals(target.getUniqueId())) return e.getKey();
        }
        return null;
    }

    public boolean isInTrade(Player player) {
        TradeSession s = activeSessions.get(player.getUniqueId());
        return s != null && s.isActive();
    }

    /**
     * Supprime la session terminée ou annulée.
     */
    public void removeSession(TradeSession session) {
        activeSessions.remove(session.getPlayerA().getUniqueId());
        activeSessions.remove(session.getPlayerB().getUniqueId());
    }

    /**
     * Supprime toutes les invitations émises ou reçues par ce joueur.
     */
    public void cleanupPlayer(Player player) {
        pendingInvites.remove(player.getUniqueId());
        pendingInvites.values().remove(player.getUniqueId());
    }
}

