package fr.redconflict.site;

import fr.redconflict.RedConflictCore;
import fr.redconflict.boutique.BoutiqueItem;
import fr.redconflict.boutique.RewardDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reprise des commandes web restées en attente.
 *
 * <p>La livraison normale passe par AzLink : le site dépose une commande, le
 * serveur vient la chercher. Ce chemin a un angle mort — si le dépôt lui-même
 * échoue, rien ne réessaie. C'est arrivé en vrai : un joueur a acheté avant
 * qu'aucun serveur ne soit déclaré dans l'administration, le site n'avait
 * personne à qui envoyer, et la commande est restée {@code pending}
 * indéfiniment, PB débités.
 *
 * <p>Cette passe regarde donc directement {@code rc_orders} et livre ce
 * qu'AzLink n'a pas livré. Elle ne remplace pas le chemin normal, qui reste plus
 * rapide : elle ne s'occupe que de ce qui traîne depuis plusieurs minutes.
 *
 * <p><b>Double livraison impossible.</b> {@link OrderService#markDelivered} ne
 * bascule la ligne que si elle est encore {@code pending}, en une seule requête.
 * Si AzLink arrive entre-temps, l'un des deux trouve la ligne déjà prise et
 * n'accorde rien.
 *
 * <p><b>Un seul serveur doit l'activer</b> — celui qui porte {@code
 * mirror-enabled}. Sans ça, un spawner acheté pourrait être livré sur le Minage
 * alors que le joueur l'attend sur le Faction.
 */
public final class OrderRetry {

    /** Au-delà, on traite le reste au tour suivant : inutile de tenir la base. */
    private static final int LOT_MAX = 20;

    private final RedConflictCore plugin;
    private final SiteDatabase site;
    private final OrderService orders;
    private final EntitlementService entitlements;

    private BukkitTask task;
    private long ageMinutes;

    public OrderRetry(RedConflictCore plugin, SiteDatabase site,
                      OrderService orders, EntitlementService entitlements) {
        this.plugin = plugin;
        this.site = site;
        this.orders = orders;
        this.entitlements = entitlements;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("site.retry-orders", true)) {
            plugin.getLogger().info("[Site] Reprise des commandes en attente : désactivée.");
            return;
        }

        // Assez long pour laisser AzLink faire son travail — il relève toutes
        // les minutes — assez court pour qu'un joueur ne s'impatiente pas.
        this.ageMinutes = Math.max(2L, plugin.getConfig().getLong("site.retry-after-minutes", 3L));

        long periodTicks = 20L * 60L * 2L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override public void run() { passe(); }
        }, 20L * 90L, periodTicks);

        plugin.getLogger().info("[Site] Reprise des commandes en attente au-delà de "
                + ageMinutes + " min.");
    }

    public void stop() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) { }
            task = null;
        }
    }

    /** Une ligne de {@code rc_orders} en souffrance. */
    private static final class EnAttente {
        final long id;
        final String name;
        final String category;
        final String itemId;
        final boolean permanent;

        EnAttente(long id, String name, String category, String itemId, boolean permanent) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.itemId = itemId;
            this.permanent = permanent;
        }
    }

    private void passe() {
        if (!site.isAvailable()) return;

        List<EnAttente> lot = new ArrayList<EnAttente>();
        String sql = "SELECT id, name, category, item_id, permanent FROM rc_orders "
                   + "WHERE status = 'pending' AND created_at < NOW() - INTERVAL ? MINUTE "
                   + "ORDER BY id LIMIT " + LOT_MAX;

        try (Connection c = site.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ageMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lot.add(new EnAttente(rs.getLong("id"), rs.getString("name"),
                            rs.getString("category"), rs.getString("item_id"),
                            rs.getBoolean("permanent")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Site] Lecture des commandes en attente : " + e.getMessage());
            return;
        }

        if (lot.isEmpty()) return;

        // La livraison touche l'inventaire et la console : thread principal.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { livrer(lot); }
        });
    }

    private void livrer(List<EnAttente> lot) {
        int livrees = 0;
        int reportees = 0;

        for (EnAttente commande : lot) {
            BoutiqueItem item = plugin.getBoutiqueCatalog().find(commande.category, commande.itemId);
            if (item == null) {
                orders.markFailed(commande.id, "Article retiré du catalogue : "
                        + commande.category + "/" + commande.itemId);
                plugin.getLogger().warning("[Site] Commande #" + commande.id
                        + " : article inconnu, marquée en échec.");
                continue;
            }

            Player online = Bukkit.getPlayerExact(commande.name);
            if (online == null && RewardDispatcher.requiresOnline(item, commande.permanent)) {
                // Un lot d'objets attend son destinataire : c'est normal, on
                // repassera. Ce n'est pas un échec.
                reportees++;
                continue;
            }

            // On ne prend la ligne QU'ICI, juste avant de donner : si AzLink
            // vient de la livrer, markDelivered renvoie false et on n'accorde
            // rien. C'est ce qui rend les deux chemins compatibles.
            if (!orders.markDelivered(commande.id)) continue;

            @SuppressWarnings("deprecation")
            OfflinePlayer cible = online != null ? online : Bukkit.getOfflinePlayer(commande.name);

            plugin.getRewardDispatcher().execute(commande.name, cible.getUniqueId(), item, commande.permanent);
            if (entitlements != null) {
                entitlements.grant(cible, item, commande.permanent, "site");
            }

            fr.redconflict.boutique.BoutiqueAnnonce.annoncer(plugin, commande.name, item.name, commande.permanent);

            if (online != null) {
                online.sendMessage("§a[Boutique] §f" + item.name
                        + " §7acheté sur le site vient de t'être remis.");
            }
            plugin.getLogger().info("[Site] Reprise : commande #" + commande.id + " ("
                    + commande.category + "/" + commande.itemId + ") livrée à " + commande.name + ".");
            livrees++;
        }

        if (livrees > 0 || reportees > 0) {
            plugin.getLogger().info("[Site] Reprise : " + livrees + " livrée(s), "
                    + reportees + " en attente du joueur.");
        }
    }
}
