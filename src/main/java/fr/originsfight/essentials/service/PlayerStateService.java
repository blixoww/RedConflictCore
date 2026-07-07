package fr.originsfight.essentials.service;

import fr.originsfight.essentials.config.EssentialsConfig;
import fr.originsfight.essentials.model.PlayerFlags;
import fr.originsfight.essentials.repository.PlayerStateRepository;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * États "confort" des joueurs : mode dieu (/god) et vol (/fly).
 * Cache mémoire pour les vérifications à chaque événement de dégâts,
 * persistance H2 pour restaurer les états à la reconnexion
 * (le vol n'est ré-appliqué que si {@code fly.persist-on-quit} est actif).
 */
public class PlayerStateService {

    public static final String GOD_PERMISSION = "redconflict.god";
    public static final String FLY_PERMISSION = "redconflict.fly";

    private final PlayerStateRepository repository;
    private final EssentialsConfig config;

    private final Set<UUID> gods = new HashSet<>();

    public PlayerStateService(PlayerStateRepository repository, EssentialsConfig config) {
        this.repository = repository;
        this.config = config;
    }

    // ── Cycle de vie (ConnectionListener / FlyListener) ────────────────────────

    /** Restaure les états persistés, en re-vérifiant les permissions actuelles. */
    public void handleJoin(Player player) {
        PlayerFlags flags = repository.find(player.getUniqueId());
        if (flags.isGod() && player.hasPermission(GOD_PERMISSION)) {
            gods.add(player.getUniqueId());
        }
        if (flags.isFly() && config.flyPersistOnQuit() && player.hasPermission(FLY_PERMISSION)) {
            player.setAllowFlight(true);
        }
    }

    /** Purge le cache mémoire ; coupe le vol persisté si la config l'exige. */
    public void handleQuit(Player player) {
        gods.remove(player.getUniqueId());
        if (!config.flyPersistOnQuit() && player.getAllowFlight()) {
            repository.saveFly(player.getUniqueId(), false);
        }
    }

    // ── God ────────────────────────────────────────────────────────────────────

    public boolean isGod(UUID player) {
        return gods.contains(player);
    }

    /** @return le nouvel état (true = god actif). */
    public boolean toggleGod(Player player) {
        boolean enabled = !gods.contains(player.getUniqueId());
        if (enabled) {
            gods.add(player.getUniqueId());
        } else {
            gods.remove(player.getUniqueId());
        }
        repository.saveGod(player.getUniqueId(), enabled);
        return enabled;
    }

    // ── Fly ────────────────────────────────────────────────────────────────────

    /** @return le nouvel état (true = vol autorisé). */
    public boolean toggleFly(Player player) {
        boolean enabled = !player.getAllowFlight();
        if (!enabled) {
            player.setFlying(false);
        }
        player.setAllowFlight(enabled);
        repository.saveFly(player.getUniqueId(), enabled);
        return enabled;
    }
}
