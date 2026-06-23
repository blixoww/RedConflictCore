package fr.originsfight.ks;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.data.PlayerDatabase;
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
    private final OriginsFightCore plugin;

    // Timestamp de connexion par joueur (pour calculer le temps de jeu)
    private static final Map<UUID, Long> joinTimes = new HashMap<>();

    public KsListener(PlayerDatabase db, OriginsFightCore plugin) {
        this.db = db;
        this.plugin = plugin;
    }

    /** Retourne le timestamp de connexion d'un joueur (pour la session en cours). */
    public static Long getJoinTime(UUID uuid) { return joinTimes.get(uuid); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        db.ensurePlayer(p);
        long now = System.currentTimeMillis();
        joinTimes.put(p.getUniqueId(), now);
        db.setJoinTime(p.getUniqueId(), now);

        // Synchronisation asynchrone des snapshots externes (balance, rank, faction)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> syncSnapshots(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Long joinTime = joinTimes.remove(p.getUniqueId());
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
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            try {
                long balance = (long) eco.getBalance(p);
                db.updateBalance(uuid, balance);
            } catch (Exception ignored) {}
        }

        // Rang (Vault Chat)
        String rank = resolveRank(p);
        db.updateRank(uuid, rank);

        // Faction
        String faction = resolveFaction(uuid, p);
        db.updateFaction(uuid, faction);
    }

    private String resolveRank(Player p) {
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) {
                Chat chat = rsp.getProvider();
                String prefix = chat.getPlayerPrefix(p);
                if (prefix == null || prefix.isEmpty()) {
                    String group = chat.getPrimaryGroup(p);
                    if (group != null && !group.isEmpty()) prefix = group;
                }
                if (prefix != null && !prefix.trim().isEmpty()) {
                    String plain = prefix.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
                    if (!plain.isEmpty()) return prefix.trim();
                }
            }
        } catch (Exception ignored) {}

        // Fallback PlaceholderAPI
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            java.lang.reflect.Method m = papi.getMethod("setPlaceholders", Player.class, String.class);
            Object out = m.invoke(null, p, "%luckperms_prefix%");
            if (out instanceof String) {
                String s = ((String) out).trim();
                if (!s.isEmpty() && !s.equals("%luckperms_prefix%")) {
                    String plain = s.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
                    if (!plain.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {}

        return "Joueur";
    }

    private String resolveFaction(UUID uuid, Player p) {
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
