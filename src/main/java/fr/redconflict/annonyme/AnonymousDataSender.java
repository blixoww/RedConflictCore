package fr.redconflict.annonyme;

import fr.redconflict.RedConflictCore;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Pousse au client l'état "anonyme" d'un joueur via le canal FACTION_S2C
 * (packet 0x82 ANONYMOUS_STATUS). Les staff avec la perm {@code staff.annonyme}
 * voient toujours les joueurs réels — donc on leur push toujours {@code false}.
 */
public final class AnonymousDataSender {

    private static final String CHANNEL = "CUSTOM:FACTION_S2C";
    private static final int    ID      = 0x82;
    private static final String STAFF_PERM = "staff.annonyme";

    private AnonymousDataSender() {}

    /** Broadcast l'état d'un joueur cible à tous les viewers en ligne (sauf le joueur lui-même). */
    public static void broadcast(Player target, boolean anonymous) {
        RedConflictCore plugin = RedConflictCore.getInstance();
        if (plugin == null || target == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue; // ne pas se masquer à soi-même
            sendTo(plugin, viewer, target.getName(), anonymous);
        }
    }

    /** Synchronise tous les joueurs anonymes actuellement en ligne vers un seul viewer (typiquement à la connexion). */
    public static void syncAll(Player viewer) {
        RedConflictCore plugin = RedConflictCore.getInstance();
        if (plugin == null || viewer == null) return;
        AnonymeManager mgr = plugin.getAnonymeManager();
        if (mgr == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean isAnon = mgr.isAnonymous(p) && !p.equals(viewer);
            sendTo(plugin, viewer, p.getName(), isAnon);
        }
    }

    private static void sendTo(RedConflictCore plugin, Player viewer, String targetName, boolean anonymous) {
        // Staff avec perm voit toujours le pseudo réel
        boolean effective = anonymous && !viewer.hasPermission(STAFF_PERM);
        byte[] payload = PacketBuilder.create(ID)
                .writeString(targetName.length() > 32 ? targetName.substring(0, 32) : targetName)
                .writeBoolean(effective)
                .build();
        viewer.sendPluginMessage((Plugin) plugin, CHANNEL, payload);
    }
}
