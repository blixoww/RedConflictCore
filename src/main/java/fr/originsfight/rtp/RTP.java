package fr.originsfight.rtp;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import fr.originsfight.utils.TimeUnits;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Random;

public class RTP {
    private static final RTP INSTANCE = new RTP();
    private final HashMap<Player, BukkitTask> tasks = new HashMap<>();

    public void isTeleporting(Player player) {
        if (player.isOp()) {
            RTP.instance().teleport(player);
            player.sendMessage("§7Téléportation effectuée.");
            return;
        }
        if (CooldownManager.instance().timeLeft(player, CooldownType.RTP) > 0) {
            player.sendMessage("§cTu dois attendre " + CooldownManager.getFormattedTimeLeft(CooldownManager.instance().timeLeft(player, CooldownType.RTP)) + " avant de pouvoir utiliser cette commande.");
        } else {
            this.tasks.put(player, new BukkitRunnable() {
                @Override
                public void run() {
                    RTP.instance().teleport(player);
                }
            }.runTaskLater(OriginsFightCore.getInstance(), 20L * 3));
            player.sendMessage("§7Téléportation dans 3 secondes.");
        }
    }

    public void teleport(Player player) {
        Location randomLocation = randomLocation();
        randomLocation.getWorld().getChunkAt(randomLocation).load();
        player.teleport(randomLocation());
        player.sendMessage("§aTéléportation effectuée.");
        CooldownManager.instance().set(player, 4, TimeUnits.HOURS, CooldownType.RTP);
        if (this.tasks.containsKey(player)) {
            tasks.get(player).cancel();
            tasks.remove(player);
        }
    }

    public Location randomLocation() {
        int max = OriginsFightCore.getInstance().getConfig().getInt("rtp.max");
        int min = OriginsFightCore.getInstance().getConfig().getInt("rtp.min");
        Random random = new Random();
        boolean isNegativeX = random.nextBoolean();
        boolean isNegativeZ = random.nextBoolean();
        int x = random.nextInt(max) + min;
        int z = random.nextInt(max) + min;
        if (isNegativeX)
            x = -x;
        if (isNegativeZ)
            z = -z;
        return new Location(Bukkit.getWorld("Faction"), x, (Bukkit.getWorld("Faction").getHighestBlockYAt(x, z) + 3), z);
    }

    public HashMap<Player, BukkitTask> getBukkitTask() {
        return tasks;
    }

    public static RTP instance() {
        return INSTANCE;
    }
}
