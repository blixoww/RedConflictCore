package fr.originsfight.utils;


public enum TimeUnits {
    SECONDS(1000),
    MINUTES(1000 * 60),
    HOURS(1000 * 60 * 60),
    DAYS(1000 * 60 * 60 * 24);

    private final long millis;

    TimeUnits(long millis) {
        this.millis = millis;
    }

    public long toMillis(long time) {
        return time * millis;
    }
}
