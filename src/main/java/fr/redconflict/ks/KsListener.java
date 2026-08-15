package fr.redconflict.ks;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.RankResolver;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.data.PlayerDatabase;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listener KS : écoute les connexions, déconnexions, kills et morts PvP.
 * Met également à jour les snapshots balance/rank/faction dans PlayerDatabase à la connexion.
 */
public class KsListener implements Listener {

    private final PlayerDatabase db;
    private final RedConflictCore plugin;

    // Timestamp de connexion par joueur (pour calculer le temps de jeu)
    private static final Map<UUID, Long> JOIN_TIMES = new HashMap<>();

    public KsListener(PlayerDatabase db, RedConflictCore plugin) {
        this.db = db;
        this.plugin = plugin;
    }

    /** Retourne le timestamp de connexion d'un joueur (pour la session en cours). */
    public static Long getJoinTime(UUID uuid) { return JOIN_TIMES.get(uuid); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        db.ensurePlayer(p);
        long now = System.currentTimeMillis();
        JOIN_TIMES.put(p.getUniqueId(), now);
        db.setJoinTime(p.getUniqueId(), now);

        // Synchronisation asynchrone des snapshots externes (balance, rank, faction)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> syncSnapshots(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Long joinTime = JOIN_TIMES.remove(p.getUniqueId());
        if (joinTime != null) {
            long seconds = (System.currentTimeMillis() - joinTime) / 1000;
            db.addPlaytime(p.getUniqueId(), seconds);
        }
    }

    /**
     * Écoute les morts PvP.
     * Incrémente les kills du tueur et les deaths de la victime.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            db.addDeath(victim.getUniqueId());
            db.addKill(killer.getUniqueId());
        }
    }

    // ── Sync snapshots ────────────────────────────────────────────────────────

    /**
     * Met à jour les snapshots balance, rank et faction dans PlayerDatabase.
     * Appelé async sur PlayerJoinEvent.
     */
    private void syncSnapshots(Player p) {
        UUID uuid = p.getUniqueId();

        // Balance (Vault Economy)
        Economy eco = VaultEconomy.get();
        if (eco != null) {
            try {
                long balance = (long) eco.getBalance(p);
                db.updateBalance(uuid, balance);
            } catch (Exception ignored) {}
        }

        // Rang (Vault Chat, secours PlaceholderAPI)
        String rank = RankResolver.resolve(p);
        db.updateRank(uuid, rank);

        // Faction
        String faction = resolveFaction(uuid, p);
        db.updateFaction(uuid, faction);
    }

    private String resolveFaction(UUID uuid, Player p) {
        if (!fr.redconflict.faction.FactionHook.isEnabled()) return "";
        try {
            if (!fr.redfaction.api.RedFactionAPI.isAvailable()) return "";
            fr.redfaction.entity.Faction fac = fr.redfaction.api.RedFactionAPI.get().getPlayerFaction(p);
            if (fac != null && fac.isNormal()) {
                String tag = fac.getTag();
                if (tag != null && !tag.isEmpty()) return tag;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
