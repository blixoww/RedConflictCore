package fr.originsfight.trade;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TradeManager {

    private static TradeManager instance;
    private final Map<UUID, TradeSession> activeSessions = new HashMap<>();
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public static TradeManager getInstance() {
        if (instance == null) instance = new TradeManager();
        return instance;
    }

    public boolean invite(Player sender, Player target) {
        if (isInTrade(sender) || isInTrade(target)) return false;
        pendingInvites.put(sender.getUniqueId(), target.getUniqueId());
        return true;
    }

    public TradeSession acceptInvite(Player inviter, Player accepter) {
        pendingInvites.remove(inviter.getUniqueId());
        TradeSession session = new TradeSession(inviter, accepter);
        activeSessions.put(inviter.getUniqueId(), session);
        activeSessions.put(accepter.getUniqueId(), session);
        TradePacketSender.sendOpen(inviter, accepter.getName(), true);
        TradePacketSender.sendOpen(accepter, inviter.getName(), false);
        // État initial : offres vides, personne n'a confirmé, 0 argent
        java.util.List<org.bukkit.inventory.ItemStack> empty = java.util.Collections.emptyList();
        TradePacketSender.sendUpdate(inviter,  empty, empty, false, false, 0L, 0L);
        TradePacketSender.sendUpdate(accepter, empty, empty, false, false, 0L, 0L);
        return session;
    }

    public void declineInvite(Player inviter) {
        pendingInvites.remove(inviter.getUniqueId());
    }

    public TradeSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public UUID getPendingTarget(Player sender) {
        return pendingInvites.get(sender.getUniqueId());
    }

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

    public void removeSession(TradeSession session) {
        activeSessions.remove(session.getPlayerA().getUniqueId());
        activeSessions.remove(session.getPlayerB().getUniqueId());
    }

    public void cleanupPlayer(Player player) {
        pendingInvites.remove(player.getUniqueId());
        pendingInvites.values().remove(player.getUniqueId());
    }
}
