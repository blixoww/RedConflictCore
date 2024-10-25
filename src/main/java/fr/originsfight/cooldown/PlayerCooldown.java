package fr.originsfight.cooldown;

import java.util.Arrays;

public class PlayerCooldown {

    private final long[] cooldowns = new long[CooldownType.values().length];

    public PlayerCooldown() {
        Arrays.fill(cooldowns, 0);
    }

    public long getCooldown(CooldownType type) {
        return cooldowns[type.ordinal()];
    }

    public void setCooldown(CooldownType type, long time) {
        cooldowns[type.ordinal()] = time;
    }

}
