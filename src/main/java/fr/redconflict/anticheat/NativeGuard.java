package fr.redconflict.anticheat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intégrité des bibliothèques natives du client : les DLL, au fichier près.
 *
 * <p><b>Le trou que ça ferme.</b> Tout le durcissement du client porte sur le
 * bytecode : l'obfuscateur renomme les classes, l'attestation empreinte le jar,
 * le rapport d'environnement inventorie les classes chargées. Les natives — les
 * {@code .dll} de LWJGL, d'OpenAL et de JInput — échappent à tout ça. Ce sont des
 * fichiers posés à côté du jar, chargés par {@code System.loadLibrary}, et
 * remplacer l'un d'eux par une version instrumentée est le chemin le plus court
 * pour exécuter du code arbitraire dans le processus du jeu sans toucher à une
 * seule classe. Rien, jusqu'ici, ne le voyait.
 *
 * <p><b>Ce qu'on reçoit.</b> Le client envoie, pour chaque fichier de son dossier
 * de natives, son nom, sa taille et son SHA-256 ; puis la liste des bibliothèques
 * natives réellement chargées dans la JVM qui ne viennent NI de ce dossier NI du
 * runtime Java. Le serveur compare la première liste à un manifeste déclaré en
 * configuration, et considère la seconde comme suspecte par nature.
 *
 * <p><b>Le mode apprentissage est le point d'entrée obligé.</b> Les natives sont
 * téléchargées chez Mojang et varient selon le système et l'architecture : aucune
 * liste écrite à la main ne serait juste. On démarre donc en {@code learn: true},
 * qui n'accuse personne et journalise un bloc YAML prêt à coller. On regarde ce
 * qui remonte pendant quelques jours, on colle, on passe à {@code false}.
 *
 * <p><b>Et la limite habituelle.</b> Le relevé est fait par le client, donc par la
 * machine du joueur : un client modifié peut mentir sur ses propres empreintes.
 * Ce que ça arrête, ce sont les injecteurs tout faits qui déposent une DLL à côté
 * du jeu sans se soucier d'être vus — c'est-à-dire la grande majorité.
 */
public class NativeGuard {

    /** Nom de fichier plausible : pas de chemin, pas d'espace, pas d'exotisme. */
    private static final int MAX_NAME = 48;

    private final Plugin plugin;
    private final ViolationTracker violations;

    /** Empreinte du dernier rapport traité, par joueur : on ne juge pas deux fois. */
    private final Map<UUID, String> lastReport = new ConcurrentHashMap<UUID, String>();

    /** Manifestes déjà journalisés en mode apprentissage, pour ne pas inonder. */
    private final Set<String> learned = new HashSet<String>();

    public NativeGuard(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    public void forget(UUID player) {
        lastReport.remove(player);
    }

    /**
     * Traite un rapport de natives.
     *
     * @param files   lignes {@code nom|taille|sha256}
     * @param foreign lignes {@code chemin} — natives chargées hors du dossier
     */
    public void handleReport(Player player, String files, String foreign) {
        if (!plugin.getConfig().getBoolean("anticheat.natives.enabled", true)) {
            return;
        }
        if (player.hasPermission("redconflict.anticheat.bypass")) {
            return;
        }
        List<Entry> entries = parse(files);
        List<String> intruders = parsePaths(foreign);

        // Un rapport identique au précédent n'apprend rien : le client renvoie
        // son manifeste dès qu'un fichier change, pas seulement à la connexion.
        String digest = entries.toString() + intruders.toString();
        if (digest.equals(lastReport.get(player.getUniqueId()))) {
            return;
        }
        lastReport.put(player.getUniqueId(), digest);

        if (plugin.getConfig().getBoolean("anticheat.natives.learn", true)) {
            learn(player, entries);
            return;
        }
        judge(player, entries);

        if (!intruders.isEmpty()) {
            // Une native chargée depuis ailleurs que le dossier du jeu et le
            // runtime Java n'a aucune raison légitime d'exister : rien dans le
            // client n'en charge.
            violations.flag(player, Check.NATIVES,
                    "bibliothèque native étrangère : " + join(intruders, 3));
        }
    }

    // ── Verdict ────────────────────────────────────────────────────────────────

    private void judge(Player player, List<Entry> entries) {
        Map<String, List<String>> allow = allowList();
        if (allow.isEmpty()) {
            // Aucun manifeste déclaré : on ne peut rien conclure, et surtout pas
            // accuser tout le monde. Même parti pris que l'attestation.
            return;
        }
        List<String> problems = new ArrayList<String>();
        Set<String> present = new HashSet<String>();

        for (Entry entry : entries) {
            present.add(entry.name);
            List<String> accepted = allow.get(entry.name);
            if (accepted == null || accepted.isEmpty()) {
                problems.add(entry.name + " (ajouté, " + kilobytes(entry.size) + " Ko)");
                continue;
            }
            boolean match = false;
            for (String hash : accepted) {
                if (hash != null && hash.trim().equalsIgnoreCase(entry.hash)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                problems.add(entry.name + " (modifié, " + kilobytes(entry.size) + " Ko)");
            }
        }
        Set<String> expected = allow.keySet();
        if (plugin.getConfig().getBoolean("anticheat.natives.flag-missing", false)) {
            for (String name : expected) {
                if (!present.contains(name)) {
                    problems.add(name + " (absent)");
                }
            }
        }
        if (!problems.isEmpty()) {
            violations.flag(player, Check.NATIVES, join(problems, 4));
        }
    }

    /**
     * Le manifeste déclaré, lu SANS passer par les chemins de configuration.
     *
     * <p>Les clés sont des noms de fichiers, donc elles contiennent un point —
     * et le point est le séparateur de chemin de l'API de configuration de
     * Bukkit. Demander {@code getStringList("lwjgl64.dll")} chercherait une
     * section {@code lwjgl64} contenant une clé {@code dll}, c'est-à-dire rien,
     * et toutes les natives seraient déclarées « ajoutées ». On lit donc les
     * valeurs brutes de la section, dont les clés sont littérales.
     */
    private Map<String, List<String>> allowList() {
        Map<String, List<String>> allow = new java.util.HashMap<String, List<String>>();
        org.bukkit.configuration.ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("anticheat.natives.allow");
        if (section == null) {
            return allow;
        }
        for (Map.Entry<String, Object> declared : section.getValues(false).entrySet()) {
            if (!(declared.getValue() instanceof List)) {
                continue;
            }
            List<String> hashes = new ArrayList<String>();
            for (Object value : (List<?>) declared.getValue()) {
                if (value != null) {
                    hashes.add(String.valueOf(value));
                }
            }
            allow.put(declared.getKey().toLowerCase(Locale.ROOT), hashes);
        }
        return allow;
    }

    /**
     * Mode apprentissage : on journalise un manifeste prêt à coller, une fois par
     * combinaison de fichiers observée.
     *
     * <p>C'est la seule façon honnête d'amorcer la liste : les natives viennent
     * de Mojang, diffèrent entre Windows, Linux et macOS, et changent à chaque
     * version de LWJGL. Les écrire à la main serait deviner.
     */
    private void learn(Player player, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        StringBuilder block = new StringBuilder();
        for (Entry entry : entries) {
            block.append("\n      ").append(entry.name).append(": [\"").append(entry.hash)
                    .append("\"]   # ").append(kilobytes(entry.size)).append(" Ko");
        }
        String text = block.toString();
        synchronized (learned) {
            if (!learned.add(text)) {
                return;
            }
        }
        plugin.getLogger().info("[AC] Natives observées chez " + player.getName()
                + " — à coller sous anticheat.natives.allow :" + text);
    }

    // ── Lecture du rapport ─────────────────────────────────────────────────────

    /**
     * Découpe le manifeste reçu, en refusant tout ce qui n'a pas la forme
     * attendue.
     *
     * <p>Le rapport vient du réseau : chaque champ est borné et validé avant
     * d'exister sous forme d'objet. Un nom avec un chemin dedans, une taille
     * absurde ou un hachage qui n'en est pas un sont jetés sans commentaire —
     * c'est une liste de fichiers, pas un langage.
     */
    private List<Entry> parse(String payload) {
        List<Entry> entries = new ArrayList<Entry>();
        if (payload == null || payload.isEmpty()) {
            return entries;
        }
        int max = Math.max(1, plugin.getConfig().getInt("anticheat.natives.max-files", 32));
        for (String line : payload.split("\n")) {
            if (entries.size() >= max) {
                break;
            }
            String[] parts = line.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            String name = parts[0].trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.length() > MAX_NAME || !name.matches("[a-z0-9._+-]+")) {
                continue;
            }
            long size;
            try {
                size = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (size < 0 || size > 512L * 1024L * 1024L) {
                continue;
            }
            String hash = parts[2].trim().toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{64}")) {
                continue;
            }
            entries.add(new Entry(name, size, hash));
        }
        return entries;
    }

    private List<String> parsePaths(String payload) {
        List<String> paths = new ArrayList<String>();
        if (payload == null || payload.isEmpty()) {
            return paths;
        }
        for (String line : payload.split("\n")) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.length() > 128) {
                continue;
            }
            // Le chemin ne sert qu'à l'alerte staff : on n'en garde que des
            // caractères imprimables, jamais de quoi fabriquer une commande.
            clean = clean.replaceAll("[^A-Za-z0-9 ._:/\\\\+-]", "");
            if (!clean.isEmpty() && paths.size() < 8 && !ignored(clean)) {
                paths.add(clean);
            }
        }
        return paths;
    }

    /** Chemins tolérés : à compléter si un antivirus ou un pilote injecte le sien. */
    private boolean ignored(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String fragment : plugin.getConfig().getStringList("anticheat.natives.ignore-paths")) {
            if (fragment != null && !fragment.isEmpty()
                    && lower.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static long kilobytes(long bytes) {
        return (bytes + 512) / 1024;
    }

    private static String join(List<String> values, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size() && i < limit; i++) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(values.get(i));
        }
        if (values.size() > limit) {
            text.append(" (+").append(values.size() - limit).append(')');
        }
        return text.toString();
    }

    /** Un fichier natif tel que le client le décrit. */
    private static final class Entry {
        private final String name;
        private final long size;
        private final String hash;

        private Entry(String name, long size, String hash) {
            this.name = name;
            this.size = size;
            this.hash = hash;
        }

        @Override
        public String toString() {
            return name + ':' + size + ':' + hash;
        }
    }
}
