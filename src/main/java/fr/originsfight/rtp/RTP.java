package fr.originsfight.rtp;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class RTP {
    private static final RTP INSTANCE = new RTP();
    private final HashMap<Player, BukkitTask> tasks = new HashMap<>();

    public void isTeleporting(Player player) {
        if (!player.isOp()) {
            if (CooldownManager.getCooldownManager().remainingTime(player, CooldownType.RTP) > 0) {
                long remaining = CooldownManager.getCooldownManager().remainingTime(player, CooldownType.RTP);
                player.sendMessage("Vous devez attendre " + CooldownManager.formatedTime(remaining) + " avant de pouvoir vous téléporter.");
            } else {
                this.tasks.put(player, new BukkitRunnable() {
                    @Override
                    public void run() {
                        RTP.getInstance().teleport(player);
                    }
                }.runTaskLater(OriginsFightCore.getInstance(), 20L * 5));
                player.sendMessage("");
            }
        }
    }

    public void teleport(Player player) {
        player.teleport(randomLocation());
        player.sendMessage("§aVous avez été téléporté avec succès.");
        CooldownManager.getCooldownManager().set(player, 2, CooldownType.RTP, TimeUnit.HOURS);
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
        return new Location(Bukkit.getWorld("world"), x, (Bukkit.getWorld("world").getHighestBlockYAt(x, z) + 3), z);
    }

    public HashMap<Player, BukkitTask> getBukkitTask() {
        return tasks;
    }

    public static RTP getInstance() {
        return INSTANCE;
    }
}
