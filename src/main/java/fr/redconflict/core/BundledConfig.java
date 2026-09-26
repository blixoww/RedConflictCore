package fr.redconflict.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copie d'un fichier de configuration embarqué dans le jar, <b>tenue à jour</b>.
 *
 * <p>{@code saveResource(path, false)} n'écrit le fichier qu'une fois : un
 * correctif d'équilibrage publié dans un nouveau jar n'atteignait donc jamais
 * un serveur déjà installé, qui gardait l'ancienne copie. C'est exactement ce
 * qui est arrivé aux récompenses des succès et aux multiplicateurs de la bourse.
 *
 * <p>Le fichier porte une ligne {@code version: N} en tête. Au démarrage, si le
 * jar embarque une version plus récente que celle du disque, l'ancien fichier
 * est mis de côté ({@code <nom>.v<N>.bak}, rien n'est perdu) et remplacé. Sans
 * changement de version, les retouches locales sont respectées.
 */
public final class BundledConfig {

    private static final Logger LOG = Logger.getLogger("RedConflictCore");
    private static final Pattern VERSION = Pattern.compile("(?m)^version:[ \\t]*([0-9]+)[ \\t]*\\r?$");

    private BundledConfig() { }

    /**
     * Garantit que {@code plugins/<plugin>/<path>} existe et n'est pas plus
     * ancien que la copie du jar.
     *
     * @return le fichier sur disque (qui peut rester absent si l'écriture échoue).
     */
    public static File refresh(JavaPlugin plugin, String path) {
        File file = new File(plugin.getDataFolder(), path);
        try {
            file.getParentFile().mkdirs();
            if (!file.exists()) {
                plugin.saveResource(path, false);
                return file;
            }

            String bundled = readResource(plugin, path);
            if (bundled == null) return file;
            int jarVersion = versionOf(bundled);
            int diskVersion = versionOf(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (jarVersion <= diskVersion) return file;

            File backup = new File(file.getParentFile(), file.getName() + ".v" + diskVersion + ".bak");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), bundled.getBytes(StandardCharsets.UTF_8));
            LOG.warning("[Config] " + path + " mis à jour (version " + diskVersion + " → " + jarVersion
                    + "). Ancienne copie conservée : " + backup.getName());
        } catch (Exception e) {
            LOG.warning("[Config] Mise à jour de " + path + " impossible : " + e.getMessage());
        }
        return file;
    }

    /** Version déclarée par le texte, 0 s'il n'en déclare pas. */
    static int versionOf(String text) {
        Matcher m = VERSION.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String readResource(JavaPlugin plugin, String path) throws Exception {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
