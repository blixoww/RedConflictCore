package fr.originsfight.bounty;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Gestionnaire des primes (bounties).
 *
 * Règles :
 *  - Un joueur ne peut placer qu'une seule prime à la fois.
 *  - Un joueur ne peut avoir qu'une seule prime sur sa tête à la fois.
 *  - La prime est retirée lorsque la cible est tuée (le tueur reçoit la somme).
 *  - Si la prime n'est pas réclamée dans les 24 heures, elle expire
 *    et l'argent est définitivement perdu.
 */
public class BountyManager {

    private static BountyManager instance;

    /** Durée de vie d'une prime en millisecondes (24 heures). */
    private static final long BOUNTY_DURATION_MS = 24L * 60L * 60L * 1000L;

    /** Clé = UUID de la cible, Valeur = infos de la prime. */
    private final Map<UUID, BountyInfo> bounties = new HashMap<>();

    /** Ensemble des joueurs ayant déjà placé une prime (UUID du commanditaire). */
    private final Map<UUID, UUID> placedBy = new HashMap<>();

    public static BountyManager getInstance() { return instance; }

    public BountyManager() { instance = this; }

    /**
     * Lance le scheduler qui vérifie toutes les 20 secondes
     * si des primes ont expiré (> 24h). Les primes expirées sont
     * retirées et un message global est diffusé. L'argent est perdu.
     */
    public void startExpirationTask(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                checkExpiredBounties();
            }
        }, 20L * 30, 20L * 20); // premier check après 30s, puis toutes les 20s
    }

    private void checkExpiredBounties() {
        long now = System.currentTimeMillis();
        // Copier les clés pour éviter ConcurrentModificationException
        List<UUID> targets = new ArrayList<>(bounties.keySet());
        for (UUID targetUuid : targets) {
            BountyInfo info = bounties.get(targetUuid);
            if (info == null) continue;
            if (now - info.getTimestamp() >= BOUNTY_DURATION_MS) {
                // Prime expirée — retirer sans rembourser
                removeBounty(targetUuid);
                // Annonce globale
                String msg = RC.fmt(RC.BOUNTY_EXPIRED_BROADCAST,
                        info.getTargetName(), info.getAmount());
                for (String line : msg.split("\n")) {
                    Bukkit.broadcastMessage(line);
                }
            }
        }
    }

    /** Vérifie si un joueur a déjà placé une prime. */
    public boolean hasPlacedBounty(UUID setter) {
        return placedBy.containsKey(setter);
    }

    /** Vérifie si une cible a déjà une prime sur sa tête. */
    public boolean hasBounty(UUID target) {
        return bounties.containsKey(target);
    }

    /** Place une prime sur un joueur. */
    public void placeBounty(UUID setter, String setterName, UUID target, String targetName, long amount) {
        BountyInfo info = new BountyInfo(setter, setterName, target, targetName, amount);
        bounties.put(target, info);
        placedBy.put(setter, target);
    }

    /** Récupère la prime sur un joueur (null si aucune). */
    public BountyInfo getBounty(UUID target) {
        return bounties.get(target);
    }

    /** Retire la prime d'un joueur et libère le commanditaire. Retourne l'info retirée. */
    public BountyInfo removeBounty(UUID target) {
        BountyInfo info = bounties.remove(target);
        if (info != null) {
            placedBy.remove(info.getSetter());
        }
        return info;
    }

    /** Retire toutes les primes placées par un joueur (en cas de déconnexion du commanditaire par ex.). */
    public BountyInfo removeBountyBySetter(UUID setter) {
        UUID target = placedBy.remove(setter);
        if (target != null) {
            return bounties.remove(target);
        }
        return null;
    }

    public Map<UUID, BountyInfo> getBounties() {
        return bounties;
    }
}
