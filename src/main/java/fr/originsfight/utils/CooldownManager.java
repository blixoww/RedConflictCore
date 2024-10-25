package fr.originsfight.utils;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.cooldown.PlayerCooldown;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CooldownManager {

    private static final CooldownManager INSTANCE = new CooldownManager();
    private final Map<String, PlayerCooldown> cooldowns;

    public CooldownManager() {
        this.cooldowns = new HashMap<>();
    }
    private PlayerCooldown getOrCreate(Player player) {
        return this.cooldowns.computeIfAbsent(player.getName(), name -> new PlayerCooldown());
    }
    public void set(Player player, long time, CooldownType type, TimeUnit unit) {
        this.getOrCreate(player).setCooldown(type, System.currentTimeMillis() + unit.toMillis(time));
    }
    public long remainingTime(Player player, CooldownType type) {
        long time = this.cooldowns.getOrDefault(player.getName(), new PlayerCooldown()).getCooldown(type);
        return Math.max(time - System.currentTimeMillis(), 0);
    }
    public boolean isOnCooldown(Player player, CooldownType type) {
        return this.remainingTime(player, type) > 0;
    }
    public void clear(Player player) {
        this.cooldowns.remove(player.getName());
    }
    public static CooldownManager getCooldownManager() {
        return INSTANCE;
    }
    public static String formatedTime(long time) {
        long hours = TimeUnit.MILLISECONDS.toHours(time);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(time) - TimeUnit.HOURS.toMinutes(hours);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(time) - TimeUnit.MINUTES.toSeconds(minutes);
        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h");
        }
        if (minutes > 0 || hours > 0) {
            builder.append(minutes).append("m");
        }
        if (seconds > 0 || minutes == 0 || hours == 0) {
            builder.append(seconds).append("s");
        }
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

}
