package fr.originsfight.useful;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import fr.originsfight.friend.FriendManager;
import fr.redfaction.api.RedFactionAPI;
import fr.redfaction.entity.Faction;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /tpu — bascule le blocage des demandes de téléportation entrantes, sauf
 * celles des amis et des membres de la même faction. La politique statique
 * {@link #shouldBlock} est branchée sur le TeleportRequestService d'essentials.
 */
public class TpuCommand extends CoreCommand {

    private static final Set<UUID> BLOCKED = new HashSet<>();

    public TpuCommand(JavaPlugin plugin) {
        super(plugin, "tpu", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (BLOCKED.remove(player.getUniqueId())) {
            player.sendMessage(RC.TPU_OFF);
        } else {
            BLOCKED.add(player.getUniqueId());
            player.sendMessage(RC.TPU_ON);
        }
    }

    /** @return true si la demande de TP de {@code requester} vers {@code target} doit être bloquée. */
    public static boolean shouldBlock(Player requester, Player target) {
        if (!BLOCKED.contains(target.getUniqueId())) {
            return false;
        }
        FriendManager friends = FriendManager.getInstance();
        if (friends != null && friends.areFriends(requester.getUniqueId(), target.getUniqueId())) {
            return false;
        }
        return !sameFaction(requester, target);
    }

    private static boolean sameFaction(Player a, Player b) {
        try {
            if (!RedFactionAPI.isAvailable()) {
                return false;
            }
            Faction factionA = RedFactionAPI.get().getPlayerFaction(a);
            Faction factionB = RedFactionAPI.get().getPlayerFaction(b);
            return factionA != null && factionB != null && factionA.getId().equals(factionB.getId());
        } catch (Exception e) {
            return false;
        }
    }
}
