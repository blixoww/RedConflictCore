package fr.redconflict.pb;

import fr.redconflict.RedConflictCore;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logger fichier pour toutes les opérations PB.
 * Fichier : plugins/RedConflictCore/pb_logs.txt
 */
public class PBLogger {

    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final Object lock = new Object();

    public PBLogger(RedConflictCore plugin) {
        this.file = new File(plugin.getDataFolder(), "pb/pb_logs.txt");
        this.file.getParentFile().mkdirs();
    }

    public void log(String action, String player, int amount, int newBalance, String reason) {
        synchronized (lock) {
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(String.format("[%s] %-12s | player=%s | delta=%d | balance=%d | reason=%s%n",
                        fmt.format(new Date()), action, player, amount, newBalance, reason));
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("Minecraft").warning("[PB] log fail: " + e.getMessage());
            }
        }
    }
}
