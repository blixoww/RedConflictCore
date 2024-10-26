package fr.originsfight.utils;

import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.cooldown.PlayerCooldown;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class CooldownManager {

    private static final CooldownManager INSTANCE = new CooldownManager();
    private final Map<String, PlayerCooldown> cooldowns;

    public CooldownManager() {
        this.cooldowns = new HashMap<>();
    }

    private PlayerCooldown getOrCreate(Player player) {
        return this.cooldowns.computeIfAbsent(player.getName(), k -> new PlayerCooldown());
    }

    public void set(Player player, long time, TimeUnits unit, CooldownType type) {
        this.getOrCreate(player).set(type, System.currentTimeMillis() + unit.toMillis(time));
    }

    public long timeLeft(Player player, CooldownType type) {
        long cooldown = this.cooldowns.getOrDefault(player.getName(), new PlayerCooldown()).get(type);
        long left = cooldown - System.currentTimeMillis();
        return Math.max(left, 0L);
    }

    public boolean isOnCooldown(Player player, CooldownType type) {
        return timeLeft(player, type) > 0;
    }
    public void clear(Player player) {
        this.cooldowns.remove(player.getName());
    }

    public static CooldownManager instance() {
        return INSTANCE;
    }

    public static String getFormattedTimeLeft(long timeLeftMillis) {
        long hours = timeLeftMillis / (1000 * 60 * 60);
        long minutes = (timeLeftMillis % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = ((timeLeftMillis % (1000 * 60 * 60)) % (1000 * 60)) / 1000;

        StringBuilder formattedTimeLeft = new StringBuilder();
        if (hours > 0) {
            formattedTimeLeft.append(hours).append("h");
        }
        if (minutes > 0 || hours > 0) {
            formattedTimeLeft.append(minutes).append("m");
        }
        if (seconds > 0 || (hours == 0 && minutes == 0)) {
            formattedTimeLeft.append(seconds).append("s");
        }

        return formattedTimeLeft.toString();
    }

}
