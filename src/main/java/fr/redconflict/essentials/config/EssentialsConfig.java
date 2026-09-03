package fr.redconflict.essentials.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Vue typée sur {@code essentials.yml} (configuration du module de commandes
 * essentielles). Rechargeable à chaud via {@code /red reload}.
 *
 * <p>Les valeurs par défaut proviennent de la ressource embarquée dans le jar :
 * une clé absente du fichier disque retombe toujours sur une valeur saine.
 */
public class EssentialsConfig {

    private static final String FILE_NAME = "essentials.yml";

    private final JavaPlugin plugin;
    private YamlConfiguration config;

    public EssentialsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** (Re)charge le fichier depuis le disque, en le créant au premier lancement. */
    public final void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource(FILE_NAME);
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.config = loaded;
    }

    public void reload() {
        load();
    }

    // ── Téléportation ──────────────────────────────────────────────────────────

    /** Délai d'attente avant une téléportation (/spawn, /home, /warp, /back, tpa). */
    public int warmupSeconds() {
        return config.getInt("teleport.warmup-seconds", 5);
    }

    public boolean cancelOnMove() {
        return config.getBoolean("teleport.cancel-on-move", true);
    }

    public boolean cancelOnDamage() {
        return config.getBoolean("teleport.cancel-on-damage", true);
    }

    /** Durée de validité d'une demande /tpa ou /tpahere. */
    public int tpaExpireSeconds() {
        return config.getInt("tpa.expire-seconds", 60);
    }

    /**
     * La commande est-elle ouverte sur ce serveur ?
     *
     * <p>Une seule liste plutôt qu'un booléen par commande : le besoin
     * (« couper /spawn sur le Minage ») se repose à l'identique pour la
     * suivante, et une clé par commande ferait trente clés pour une seule
     * question. Les noms sont ceux de plugin.yml, sans la barre oblique.
     */
    public boolean commandEnabled(String command) {
        for (String disabled : config.getStringList("disabled-commands")) {
            if (disabled != null && disabled.equalsIgnoreCase(command)) {
                return false;
            }
        }
        return true;
    }

    /** Cooldown (en secondes) d'une commande, 0 = aucun. Clé : {@code cooldowns.<commande>}. */
    public int cooldownSeconds(String command) {
        return config.getInt("cooldowns." + command, 0);
    }

    // ── /back ──────────────────────────────────────────────────────────────────

    /** Enregistrer la position de mort pour permettre /back après un respawn. */
    public boolean backOnDeath() {
        return config.getBoolean("back.save-on-death", true);
    }

    // ── États joueur ───────────────────────────────────────────────────────────

    /** true = /fly reste actif à la reconnexion ; false = coupé à la déconnexion. */
    public boolean flyPersistOnQuit() {
        return config.getBoolean("fly.persist-on-quit", true);
    }

    public int speedMax() {
        return config.getInt("speed.max", 10);
    }

    // ── Homes ──────────────────────────────────────────────────────────────────

    /** Nombre de homes sans permission {@code redconflict.sethome.multiple.<n>}. */
    public int homesDefaultMax() {
        return config.getInt("homes.default-max", 1);
    }

    /** Borne haute du scan des permissions multiple.&lt;n&gt;. */
    public int homesPermissionScanMax() {
        return config.getInt("homes.permission-scan-max", 30);
    }

    // ── Warps ──────────────────────────────────────────────────────────────────

    /** true = chaque warp exige la permission {@code redconflict.warp.<nom>}. */
    public boolean perWarpPermission() {
        return config.getBoolean("warps.per-warp-permission", false);
    }

    // ── Social ─────────────────────────────────────────────────────────────────

    public int nearDefaultRadius() {
        return config.getInt("near.default-radius", 100);
    }

    public int nearMaxRadius() {
        return config.getInt("near.max-radius", 300);
    }

    public int helpPerPage() {
        return config.getInt("help.per-page", 8);
    }

    // ── Économie ───────────────────────────────────────────────────────────────

    /** true = RedConflictCore fournit le provider Vault Economy (remplace EssentialsX). */
    public boolean economyEnabled() {
        return config.getBoolean("economy.enabled", true);
    }

    public double startingBalance() {
        return config.getDouble("economy.starting-balance", 0.0);
    }

    /** Symbole affiché par le provider économie interne (ex. "$"). */
    public String currencySymbol() {
        return config.getString("economy.currency-symbol", "$");
    }
}
