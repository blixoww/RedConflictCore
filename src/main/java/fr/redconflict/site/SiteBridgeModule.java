package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.data.PlayerDataServerHandler;
import fr.redconflict.pb.SitePBLedger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le pont entre le serveur de jeu et le site.
 *
 * <p>Rassemble tout ce qui traverse la frontière : la base partagée, le miroir
 * des profils, la publication du catalogue, les droits possédés, les commandes
 * web et le rafraîchissement du solde PB.
 *
 * <p><b>À n'activer que sur un seul serveur de la grappe.</b> Faction et Minage
 * lisent la même base H2 ; les deux écriraient les mêmes lignes en concurrence,
 * pour rien. On l'active sur celui qui héberge H2 — le Faction.
 */
public final class SiteBridgeModule implements Module, Listener {

    private final RedConflictCore plugin;

    private SiteDatabase database;
    private EntitlementService entitlements;
    private CatalogExporter catalogExporter;
    private OrderService orders;
    private SiteSync sync;
    private OrderRetry retry;

    private BukkitTask maintenanceTask;
    private BukkitTask refreshTask;

    /** Dernier solde poussé au client, pour ne rien renvoyer quand rien ne bouge. */
    private final Map<UUID, Integer> lastPushed = new HashMap<>();

    public SiteBridgeModule(RedConflictCore plugin, SiteDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "SiteBridge";
    }

    @Override
    public void enable() {
        // Le pool est ouvert par RedConflictCore, avant l'installation des
        // modules : le ledger PB en dépend et il est installé avant celui-ci.
        if (database == null || !database.isAvailable()) {
            // Site désactivé ou injoignable : le serveur démarre quand même. La
            // boutique PB refusera les achats — voir PBManager.isAvailable().
            plugin.getLogger().info("[Site] Pont inactif : boutique web et droits non synchronisés.");
            return;
        }

        this.entitlements = new EntitlementService(plugin, database, plugin.getBoutiqueCatalog());
        this.catalogExporter = new CatalogExporter(plugin, database, plugin.getBoutiqueCatalog());
        this.orders = new OrderService(plugin, database);

        new CommandRegistrar(plugin).register("rcdeliver", new SiteDeliverCommand(plugin));
        Bukkit.getPluginManager().registerEvents(this, plugin);

        this.sync = new SiteSync(plugin, plugin.getCoreDatabase(), database);
        sync.start();

        // Publication du catalogue, hors du démarrage : au boot, le serveur a
        // mieux à faire qu'une transaction réseau.
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {
            @Override public void run() { catalogExporter.export(); }
        }, 20L * 15L);

        startMaintenance();
        startBalanceRefresh();

        // Filet sous AzLink : une commande dont le dépôt a échoué ne serait
        // jamais reprise autrement. N'a lieu d'être que sur le serveur qui porte
        // aussi le miroir — sinon un spawner pourrait être livré sur le Minage
        // alors que le joueur l'attend sur le Faction.
        if (plugin.getConfig().getBoolean("site.mirror-enabled", true)) {
            this.retry = new OrderRetry(plugin, database, orders, entitlements);
            retry.start();
        }

        plugin.getLogger().info("[Site] Pont actif : catalogue, droits, commandes web et solde PB partagés.");
    }

    @Override
    public void disable() {
        if (maintenanceTask != null) { try { maintenanceTask.cancel(); } catch (Exception ignored) { } }
        if (refreshTask != null)     { try { refreshTask.cancel();     } catch (Exception ignored) { } }
        if (retry != null) retry.stop();
        if (sync != null) sync.close();
        if (database != null) database.close();
    }

    // ── Accès pour le reste du plugin ──────────────────────────────────────────

    /**
     * Force un passage du miroir, tout de suite et sur le thread appelant.
     *
     * <p>Sert à l'extinction : la tâche périodique ne tournera plus, et le temps de jeu
     * vient tout juste d'être écrit. Sans ce passage, la dernière session de chaque joueur
     * n'atteindrait le site qu'au prochain démarrage du serveur.
     */
    public void syncNow() {
        if (sync != null) sync.syncNow();
    }

    public SiteDatabase getDatabase()          { return database; }
    public EntitlementService getEntitlements() { return entitlements; }
    public OrderService getOrders()            { return orders; }

    /** Republie le catalogue — appelée après un {@code /pbshop reload}. */
    public void republishCatalog() {
        if (catalogExporter == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() { catalogExporter.export(); }
        });
    }

    // ── Connexion d'un joueur ──────────────────────────────────────────────────

    /**
     * Réconcilie ses droits, et pousse son solde.
     *
     * <p>{@code MONITOR} : on veut l'état des permissions tel qu'il est une fois
     * que LuckPerms et les autres plugins ont fini de charger le joueur, pas au
     * milieu.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Un tick de retard : à MONITOR, LuckPerms a chargé l'utilisateur, mais
        // certains grades sont posés par des plugins tiers dans le même tick.
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                if (entitlements != null) entitlements.reconcile(player);
            }
        }, 20L);
    }

    // ── Tâches de fond ─────────────────────────────────────────────────────────

    /** Purge des droits temporaires arrivés à terme, une fois par heure. */
    private void startMaintenance() {
        long periodTicks = 20L * 60L * 60L;
        maintenanceTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                if (entitlements == null) return;
                int purged = entitlements.purgeExpired();
                if (purged > 0) {
                    plugin.getLogger().info("[Site] " + purged + " droits temporaires expirés retirés.");
                }
            }
        }, 20L * 60L, periodTicks);
    }

    /**
     * Rafraîchit le solde PB affiché en jeu.
     *
     * <p>Depuis que le ledger est partagé, un solde peut changer sans que le
     * serveur de jeu y soit pour quoi que ce soit : un achat sur le site, un
     * paiement validé. Sans ce rappel, le joueur verrait son ancien solde jusqu'à
     * sa prochaine opération en jeu, et croirait avoir payé pour rien.
     *
     * <p>Une seule requête pour tous les connectés, et on ne renvoie un paquet
     * que si la valeur a bougé.
     */
    private void startBalanceRefresh() {
        long periodTicks = 20L * Math.max(5L, plugin.getConfig().getLong("site.refresh-seconds", 20L));
        // Le tour commence sur le thread principal : la liste des connectés s'y
        // lit sans risque, la requête part ensuite en asynchrone.
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                if (database == null || !database.isAvailable()) return;

                Collection<? extends Player> online = Bukkit.getOnlinePlayers();
                if (online.isEmpty()) {
                    lastPushed.clear();
                    return;
                }
                final List<UUID> uuids = new ArrayList<UUID>(online.size());
                for (Player p : online) uuids.add(p.getUniqueId());

                Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                    @Override public void run() { refreshBalances(uuids); }
                });
            }
        }, periodTicks, periodTicks);
    }

    private void refreshBalances(List<UUID> uuids) {
        // Le solde est users.money, joint par game_id — l'UUID sans tirets.
        // On garde la correspondance des deux formes pour rendre le résultat.
        Map<String, UUID> byGameId = new HashMap<String, UUID>(uuids.size() * 2);
        for (UUID uuid : uuids) byGameId.put(SitePBLedger.gameId(uuid), uuid);

        StringBuilder sql = new StringBuilder("SELECT game_id, money FROM users WHERE game_id IN (");
        for (int i = 0; i < uuids.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(')');

        final Map<UUID, Integer> balances = new HashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID uuid : uuids) ps.setString(index++, SitePBLedger.gameId(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = byGameId.get(rs.getString("game_id"));
                    if (uuid == null) continue;
                    BigDecimal money = rs.getBigDecimal("money");
                    if (money == null) continue;
                    balances.put(uuid, money.setScale(0, RoundingMode.FLOOR).intValue());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Site] Rafraîchissement des soldes PB : " + e.getMessage());
            return;
        }

        // L'envoi de paquets se fait sur le thread principal.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                lastPushed.keySet().retainAll(balances.keySet());
                for (Map.Entry<UUID, Integer> entry : balances.entrySet()) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p == null || !p.isOnline()) continue;
                    Integer previous = lastPushed.get(entry.getKey());
                    if (previous != null && previous.equals(entry.getValue())) continue;
                    lastPushed.put(entry.getKey(), entry.getValue());
                    try {
                        PlayerDataServerHandler.sendPB(p, entry.getValue());
                    } catch (Exception ignored) { }
                }
            }
        });
    }
}
