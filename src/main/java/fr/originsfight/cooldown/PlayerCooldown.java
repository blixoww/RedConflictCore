package fr.originsfight.cooldown;

import java.util.Arrays;

public class PlayerCooldown {

    private final long[] cooldowns = new long[CooldownType.values().length];

    public PlayerCooldown() {
        Arrays.fill(cooldowns, 0);
    }

    public long get(CooldownType type) {
        return cooldowns[type.ordinal()];
    }

    public void set(CooldownType type, long time) {
        cooldowns[type.ordinal()] = time;
    }

}
