package fr.redconflict.boutique;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Module boutique PB : boutique rendue côté client moddé (/pbshop) — grades,
 * commandes, spawners et offres spéciales à rotation. Porte la configuration
 * {@code boutique/boutique.yml} (défauts embarqués dans le jar).
 */
public class PBShopModule implements Module {

    private final RedConflictCore plugin;

    private FileConfiguration boutiqueConfig;
    private OffresManager offresManager;
    private BoutiqueCatalog catalog;
    private RewardDispatcher rewards;

    public PBShopModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "PBShop";
    }

    @Override
    public void enable() {
        loadBoutiqueConfig();

        // Le catalogue et le distributeur de récompenses vivent ici, pas dans le
        // pont vers le site : la boutique en jeu doit continuer de marcher même
        // quand le site est éteint.
        this.catalog = new BoutiqueCatalog(plugin);
        this.catalog.reload();
        this.rewards = new RewardDispatcher(plugin);

        this.offresManager = new OffresManager(plugin);
        offresManager.start();

        new CommandRegistrar(plugin).register("pbshop", new BoutiqueCommand(plugin));

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,
                BoutiqueClientServerHandler.CHANNEL_C2S, new BoutiqueClientServerHandler(plugin));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BoutiquePacketSender.CHANNEL_S2C);

        plugin.getLogger().info("[PBShop] Boutique PB (client-side) initialisée ("
                + offresManager.listIds().size() + " offres définies).");
    }

    @Override
    public void disable() {
        if (offresManager != null) {
            offresManager.stop();
        }
    }

    /** Charge boutique/boutique.yml (créé au premier lancement, défauts du jar). */
    private void loadBoutiqueConfig() {
        File file = new File(plugin.getDataFolder(), "boutique/boutique.yml");
        file.getParentFile().mkdirs();
        if (!file.exists()) {
            plugin.saveResource("boutique/boutique.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        // Le chemin de la ressource est celui du jar, dossier compris. Avec un
        // simple "boutique.yml" la recherche echouait sans bruit : les serveurs
        // dont le fichier datait d'avant une nouvelle cle la lisaient absente,
        // et la fonctionnalite restait muette (cas de boutique.annonce).
        InputStream defaults = plugin.getResource("boutique/boutique.yml");
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.boutiqueConfig = loaded;
    }

    public FileConfiguration getBoutiqueConfig() {
        return boutiqueConfig;
    }

    public BoutiqueCatalog getCatalog() {
        return catalog;
    }

    public RewardDispatcher getRewards() {
        return rewards;
    }

    /**
     * Relit {@code boutique.yml} et le catalogue. Appelée par {@code /pbshop
     * reload} ; la republication vers le site est déclenchée par le pont, qui
     * observe ce rechargement.
     */
    public void reloadCatalog() {
        loadBoutiqueConfig();
        if (catalog != null) catalog.reload();
    }

    public OffresManager getOffresManager() {
        return offresManager;
    }
}
