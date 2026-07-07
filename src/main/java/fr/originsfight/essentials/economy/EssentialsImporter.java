package fr.originsfight.essentials.economy;

import fr.originsfight.core.text.Text;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.AccountRepository;
import fr.originsfight.essentials.repository.HomeRepository;
import fr.originsfight.essentials.repository.SeenRepository;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.UUID;

/**
 * Import unique des données EssentialsX ({@code plugins/Essentials/userdata/*.yml})
 * vers la base H2 : soldes, homes et traces de connexion (/seen).
 *
 * <p>À lancer une fois par serveur via {@code /red import essentials} AVANT de
 * retirer les jars EssentialsX. Sans {@code force}, les données déjà présentes
 * en base ne sont jamais écrasées (relançable sans risque).
 */
public class EssentialsImporter {

    private final Plugin plugin;
    private final AccountRepository accounts;
    private final HomeRepository homes;
    private final SeenRepository seen;

    public EssentialsImporter(Plugin plugin, AccountRepository accounts,
                              HomeRepository homes, SeenRepository seen) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.homes = homes;
        this.seen = seen;
    }

    /** Lance l'import en asynchrone (lecture fichiers + SQL hors thread principal). */
    public void runAsync(final CommandSender sender, final boolean force) {
        final File userdata = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
        if (!userdata.isDirectory()) {
            sender.sendMessage(Text.error("Dossier introuvable : plugins/Essentials/userdata"));
            return;
        }
        sender.sendMessage(Text.info("Import des données Essentials en cours..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Report report = importAll(userdata, force);
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.success(
                    "Import terminé : §f" + report.balances + " §asoldes, §f" + report.homes
                            + " §ahomes, §f" + report.seen + " §atraces /seen §7("
                            + report.skipped + " déjà présents ignorés, " + report.errors + " erreurs)")));
        });
    }

    private Report importAll(File userdata, boolean force) {
        Report report = new Report();
        File[] files = userdata.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return report;

        for (File file : files) {
            UUID uuid = parseUuid(file.getName());
            if (uuid == null) continue;
            try {
                importFile(uuid, YamlConfiguration.loadConfiguration(file), force, report);
            } catch (Exception e) {
                report.errors++;
                plugin.getLogger().warning("[Import] Fichier " + file.getName() + " illisible : " + e.getMessage());
            }
        }
        return report;
    }

    private void importFile(UUID uuid, YamlConfiguration data, boolean force, Report report) {
        String name = data.getString("last-account-name", data.getString("lastAccountName", ""));

        // Solde (stocké en chaîne par Essentials).
        Object money = data.get("money");
        if (money != null) {
            if (!force && accounts.exists(uuid)) {
                report.skipped++;
            } else {
                try {
                    accounts.save(uuid, name, Double.parseDouble(String.valueOf(money)));
                    report.balances++;
                } catch (NumberFormatException e) {
                    report.errors++;
                }
            }
        }

        // Homes (rattachés au serveur courant : lancer l'import sur chaque serveur).
        ConfigurationSection homesSection = data.getConfigurationSection("homes");
        if (homesSection != null) {
            for (String homeName : homesSection.getKeys(false)) {
                ConfigurationSection h = homesSection.getConfigurationSection(homeName);
                if (h == null || h.getString("world") == null) continue;
                String normalized = homeName.toLowerCase(java.util.Locale.ROOT);
                if (!force && homes.exists(uuid, normalized)) {
                    report.skipped++;
                    continue;
                }
                homes.save(uuid, normalized, new StoredLocation(
                        h.getString("world"),
                        h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                        (float) h.getDouble("yaw"), (float) h.getDouble("pitch")));
                report.homes++;
            }
        }

        // Traces /seen (login/logout Essentials, en millisecondes epoch).
        long login = data.getLong("timestamps.login", 0L);
        long logout = data.getLong("timestamps.logout", 0L);
        if (login > 0 && seen.find(uuid) == null && !name.isEmpty()) {
            seen.recordJoin(uuid, name, login);
            if (logout > 0) {
                seen.recordQuit(uuid, logout);
            }
            report.seen++;
        }
    }

    private UUID parseUuid(String fileName) {
        try {
            return UUID.fromString(fileName.substring(0, fileName.length() - ".yml".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Report {
        int balances;
        int homes;
        int seen;
        int skipped;
        int errors;
    }
}
