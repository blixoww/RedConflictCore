package fr.originsfight.useful;

import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import fr.originsfight.RC;
import fr.originsfight.friend.FriendManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /tpu — Toggle "TP Unavailable".
 * Quand actif, bloque toutes les demandes de téléportation entrantes
 * sauf celles des amis et membres de la même faction.
 */
public class TpuCommand implements CommandExecutor {

    private static final Set<UUID> blocked = new HashSet<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        Player p = (Player) sender;
        UUID uid = p.getUniqueId();
        if (blocked.contains(uid)) {
            blocked.remove(uid);
            p.sendMessage(RC.TPU_OFF);
        } else {
            blocked.add(uid);
            p.sendMessage(RC.TPU_ON);
        }
        return true;
    }

    /**
     * Retourne true si la demande de TP de {@code requester} vers {@code target} doit être bloquée.
     * Appelé par tout système de TP (tpa, tpahere, etc.) avant d'envoyer la demande.
     */
    public static boolean shouldBlock(Player requester, Player target) {
        if (!blocked.contains(target.getUniqueId())) return false;
        FriendManager fm = FriendManager.getInstance();
        if (fm != null && fm.areFriends(requester.getUniqueId(), target.getUniqueId())) return false;
        return !sameFaction(requester, target);
    }

    public static boolean isActive(UUID playerUid) {
        return blocked.contains(playerUid);
    }

    // ── Vérification faction via l'API RedFaction ──

    private static boolean sameFaction(Player a, Player b) {
        try {
            if (!RedFactionAPI.isAvailable()) return false;
            RedFactionAPI api = RedFactionAPI.get();
            Faction fA = api.getPlayerFaction(a);
            Faction fB = api.getPlayerFaction(b);
            if (fA == null || fB == null) return false;
            return fA.getId().equals(fB.getId());
        } catch (Exception ignored) {}
        return false;
    }
}
