package fr.originsfight.essentials.service;

import fr.originsfight.essentials.config.EssentialsConfig;
import fr.originsfight.essentials.model.StoredLocation;
import fr.originsfight.essentials.repository.HomeRepository;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Homes des joueurs : validation des noms, limite par permission
 * ({@code redconflict.sethome.multiple.<n>}, comme essentials.sethome.multiple.X)
 * et accès à la persistance.
 */
public class HomeService {

    /** Permission donnant un nombre illimité de homes. */
    public static final String UNLIMITED_PERMISSION = "redconflict.sethome.multiple.unlimited";

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]{1,16}");

    /** Résultat d'une tentative de création de home. */
    public enum SetResult {
        CREATED, REPLACED, INVALID_NAME, LIMIT_REACHED
    }

    private final HomeRepository repository;
    private final EssentialsConfig config;

    public HomeService(HomeRepository repository, EssentialsConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /** Homes du joueur (nom → position), ordonnés alphabétiquement. */
    public Map<String, StoredLocation> list(UUID player) {
        return repository.findAll(player);
    }

    /** @return la position du home, ou {@code null} (inexistant ou monde déchargé). */
    public Location find(UUID player, String name) {
        StoredLocation stored = repository.find(player, normalize(name));
        return stored != null ? stored.toLocation() : null;
    }

    public boolean exists(UUID player, String name) {
        return repository.exists(player, normalize(name));
    }

    /** Crée ou remplace un home à la position actuelle du joueur, dans la limite autorisée. */
    public SetResult set(Player player, String name) {
        String normalized = normalize(name);
        if (!VALID_NAME.matcher(normalized).matches()) {
            return SetResult.INVALID_NAME;
        }
        boolean replacing = repository.exists(player.getUniqueId(), normalized);
        if (!replacing && repository.count(player.getUniqueId()) >= maxHomes(player)) {
            return SetResult.LIMIT_REACHED;
        }
        repository.save(player.getUniqueId(), normalized, StoredLocation.of(player.getLocation()));
        return replacing ? SetResult.REPLACED : SetResult.CREATED;
    }

    public boolean delete(UUID player, String name) {
        return repository.delete(player, normalize(name));
    }

    /**
     * Limite de homes du joueur : illimité avec {@link #UNLIMITED_PERMISSION},
     * sinon le plus grand {@code redconflict.sethome.multiple.<n>} détenu
     * (scan borné par la config), sinon la limite par défaut.
     */
    public int maxHomes(Player player) {
        if (player.hasPermission(UNLIMITED_PERMISSION)) {
            return Integer.MAX_VALUE;
        }
        int defaultMax = config.homesDefaultMax();
        for (int n = config.homesPermissionScanMax(); n > defaultMax; n--) {
            if (player.hasPermission("redconflict.sethome.multiple." + n)) {
                return n;
            }
        }
        return defaultMax;
    }

    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
