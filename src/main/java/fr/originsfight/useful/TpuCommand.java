package fr.originsfight.useful;

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

    // ── Vérification faction via reflection (compatible MassiveCraft / FactionsUUID) ──

    private static boolean sameFaction(Player a, Player b) {
        // FactionsUUID / MassiveCraft moderne
        try {
            Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
            Object fpAll = fpClass.getMethod("getInstance").invoke(null);
            Object fpA   = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, a);
            Object fpB   = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, b);
            if (fpA == null || fpB == null) return false;
            Object fA = fpA.getClass().getMethod("getFaction").invoke(fpA);
            Object fB = fpB.getClass().getMethod("getFaction").invoke(fpB);
            if (fA == null || fB == null) return false;
            String idA = (String) fA.getClass().getMethod("getId").invoke(fA);
            String idB = (String) fB.getClass().getMethod("getId").invoke(fB);
            return idA != null && idA.equals(idB);
        } catch (Exception ignored) {}

        // MassiveCraft legacy
        try {
            Class<?> mpClass = Class.forName("com.massivecraft.factions.entity.MPlayer");
            Object mpA = mpClass.getMethod("get", UUID.class).invoke(null, a.getUniqueId());
            Object mpB = mpClass.getMethod("get", UUID.class).invoke(null, b.getUniqueId());
            if (mpA == null || mpB == null) return false;
            Object fA = mpA.getClass().getMethod("getFaction").invoke(mpA);
            Object fB = mpB.getClass().getMethod("getFaction").invoke(mpB);
            if (fA == null || fB == null) return false;
            String nA = (String) fA.getClass().getMethod("getName").invoke(fA);
            String nB = (String) fB.getClass().getMethod("getName").invoke(fB);
            return nA != null && nA.equals(nB);
        } catch (Exception ignored) {}

        return false;
    }
}
