package fr.originsfight.staff;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Centralise tous les messages formatés du système staff.
 */
public class StaffFormatter {

    // ── Helper Java 8 ─────────────────────────────────────────────────────────
    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    public static final String PREFIX    = "§8[§c§lStaff§8] §r";
    public static final String SEPARATOR;
    public static final String DATE_FMT  = "dd/MM/yyyy HH:mm";

    static {
        SEPARATOR = "§8" + repeat("-", 32);
    }

    private static final SimpleDateFormat SDF = new SimpleDateFormat(DATE_FMT);

    // ── Messages → joueur sanctionné (retournent des tableaux de lignes) ──────

    public static String banScreen(String reason, String expiry, String staff) {
        return "\n§c§l>> Vous avez ete banni <<\n\n" +
               "§7Raison   §f: §c" + reason + "\n" +
               "§7Staff    §f: §e" + staff + "\n" +
               "§7Expire   §f: §a" + expiry + "\n\n" +
               "§7Contestation §f: §bdiscord.gg/originsfight";
    }

    public static String muteMessage(String reason, String expiry) {
        return PREFIX + "§cVous etes mute. §7Raison : §f" + reason +
               " §7| Expire : §f" + expiry;
    }

    /** Retourne les lignes du message freeze (à envoyer une par une). */
    public static String[] freezeLines(String staffName) {
        return new String[]{
            SEPARATOR,
            PREFIX + "§b§lVous avez ete FREEZE !",
            "§7Staff : §c" + staffName,
            "§cNe vous deconnectez pas §7— un staff arrive.",
            SEPARATOR
        };
    }

    // ── Méthodes de compatibilité (une seule ligne) ───────────────────────────

    public static String warnMessage(int count, String reason, String staff) {
        return PREFIX + "§e§lWarn #" + count + " §f: §e" + reason + " §7(par §c" + staff + "§7)";
    }

    public static String freezeMessage(String staffName) {
        return PREFIX + "§b§lFreeze ! §7Staff : §c" + staffName + " §7— Ne vous deconnectez pas !";
    }

    // ── Messages → staff (une seule ligne chacun) ─────────────────────────────

    public static String sanctionBroadcastBan(String target, String reason, String expiry, String staff) {
        return PREFIX + "§c[BAN] §f" + target + " §8| §c" + reason + " §8| §e" + staff + " §8| §a" + expiry;
    }

    public static String sanctionBroadcastMute(String target, String reason, String expiry, String staff) {
        return PREFIX + "§6[MUTE] §f" + target + " §8| §e" + reason + " §8| §c" + staff + " §8| §7" + expiry;
    }

    public static String sanctionBroadcastWarn(String target, String reason, String staff) {
        return PREFIX + "§e[WARN] §f" + target + " §8| §e" + reason + " §8| §c" + staff;
    }

    public static String sanctionBroadcastKick(String target, String reason, String staff) {
        return PREFIX + "§d[KICK] §f" + target + " §8| §e" + reason + " §8| §c" + staff;
    }

    // ── Historique ────────────────────────────────────────────────────────────

    public static void sendHistoryHeader(org.bukkit.command.CommandSender s, String playerName, int count) {
        s.sendMessage("§8[§c§lSanctions§8] §f" + playerName + " §8(" + count + ")");
    }

    /** Envoie une entrée de sanction sur 2 lignes compactes. */
    public static void sendHistoryEntry(org.bukkit.command.CommandSender sender, StaffDatabase.Sanction s) {
        String icon;
        switch (s.type) {
            case WARN: icon = "§e[W]"; break;
            case MUTE: icon = "§6[M]"; break;
            case BAN:  icon = "§c[B]"; break;
            case KICK: icon = "§d[K]"; break;
            default:   icon = "§7[?]"; break;
        }
        String status = (s.active && !s.isExpired()) ? "§a+" : "§8-";
        String expiry = s.isPermanent() ? "Perm" : formatDate(s.expiresAt);
        sender.sendMessage(icon + " " + status + " §f" + s.reason + " §8(" + expiry + ")");
        sender.sendMessage("  §8" + s.staff + " §7le §f" + formatDate(s.issuedAt));
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    public static String formatDate(long timestamp) {
        return SDF.format(new Date(timestamp));
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "Permanent";
        long seconds = ms / 1000;
        long days  = TimeUnit.SECONDS.toDays(seconds);
        long hours = TimeUnit.SECONDS.toHours(seconds) % 24;
        long mins  = TimeUnit.SECONDS.toMinutes(seconds) % 60;
        StringBuilder sb = new StringBuilder();
        if (days  > 0) sb.append(days).append("j ");
        if (hours > 0) sb.append(hours).append("h ");
        if (mins  > 0) sb.append(mins).append("min");
        String result = sb.toString().trim();
        return result.isEmpty() ? "< 1 min" : result;
    }

    /**
     * Parse une durée humaine (ex : "1j", "2h", "30m", "7d") en millisecondes.
     * Retourne -1 si permanent.
     */
    public static long parseDuration(String s) {
        s = s.toLowerCase().trim();
        if (s.equals("perm") || s.equals("p") || s.equals("permanent")) return -1;
        long factor;
        if (s.endsWith("d") || s.endsWith("j"))      factor = 86400000L;
        else if (s.endsWith("h"))                     factor = 3600000L;
        else if (s.endsWith("m"))                     factor = 60000L;
        else if (s.endsWith("s"))                     factor = 1000L;
        else return -1;
        try {
            long val = Long.parseLong(s.substring(0, s.length() - 1));
            return val * factor;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String expiryLabel(long duration) {
        if (duration <= 0) return "Permanent";
        return formatDuration(duration) + " (expire le " + formatDate(System.currentTimeMillis() + duration) + ")";
    }
}

