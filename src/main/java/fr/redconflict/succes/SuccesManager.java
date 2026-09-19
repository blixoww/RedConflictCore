package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.ks.KsListener;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Avancement, déblocage et versement des succès.
 *
 * <p><b>Un seul chemin d'entrée.</b> Tout ce qui se passe en jeu arrive ici par
 * {@link #progress(Player, SuccesTrigger, String, int)} : le code appelant ne
 * sait pas quels succès existent, il annonce un fait (« ce joueur a cassé
 * 3 obsidiennes ») et le catalogue décide qui avance. Ajouter un succès sur un
 * fait déjà annoncé ne demande donc aucune modification de code.
 *
 * <p><b>Le cache est la vérité en jeu, la base la vérité au repos.</b> Les
 * compteurs vivent en mémoire tant que le joueur est connecté — un bloc cassé
 * ne doit pas coûter un aller-retour SQL — et sont écrits à chaque changement
 * d'état notable (déblocage, réclamation) puis à la déconnexion.
 */
public class SuccesManager {

    private static final Logger LOG = Logger.getLogger("Succes");

    /**
     * Accès statique, réservé aux points d'accroche qui ne peuvent pas recevoir
     * d'injection — l'hôtel des ventes et la bourse appellent depuis des
     * gestionnaires construits bien avant ce module. Même entorse que
     * {@code JobManager.getInstance()}, et pour la même raison.
     */
    private static SuccesManager instance;

    private final RedConflictCore plugin;
    private final SuccesDatabase database;
    private final SuccesCatalog catalog;
    private SuccesPacketSender packets;
    private Economy economy;

    /** UUID → (identifiant de succès → avancement). Uniquement les connectés. */
    private final Map<UUID, Map<String, SuccesDatabase.Entry>> cache = new ConcurrentHashMap<>();

    public SuccesManager(RedConflictCore plugin, SuccesDatabase database, SuccesCatalog catalog) {
        this.plugin = plugin;
        this.database = database;
        this.catalog = catalog;
        instance = this;
        setupEconomy();
    }

    /** @return le gestionnaire actif, ou {@code null} si le module est désactivé. */
    public static SuccesManager get() {
        return instance;
    }

    public void setPacketSender(SuccesPacketSender packets) {
        this.packets = packets;
    }

    public SuccesCatalog getCatalog() {
        return catalog;
    }

    public SuccesDatabase getDatabase() {
        return database;
    }

    // ── Cycle de vie d'un joueur ─────────────────────────────────────────────

    public void load(Player player) {
        cache.put(player.getUniqueId(), new HashMap<>(database.load(player.getUniqueId())));
    }

    public void unload(UUID uuid) {
        Map<String, SuccesDatabase.Entry> entries = cache.remove(uuid);
        if (entries == null) return;
        for (Map.Entry<String, SuccesDatabase.Entry> e : entries.entrySet()) {
            database.save(uuid, e.getKey(), e.getValue());
        }
    }

    public void saveAll() {
        for (UUID uuid : new ArrayList<>(cache.keySet())) {
            Map<String, SuccesDatabase.Entry> entries = cache.get(uuid);
            if (entries == null) continue;
            for (Map.Entry<String, SuccesDatabase.Entry> e : entries.entrySet()) {
                database.save(uuid, e.getKey(), e.getValue());
            }
        }
    }

    /** Avancement d'un joueur connecté sur un succès ; jamais {@code null}. */
    public SuccesDatabase.Entry entryOf(UUID uuid, String succesId) {
        Map<String, SuccesDatabase.Entry> entries = cache.get(uuid);
        if (entries == null) return new SuccesDatabase.Entry();
        SuccesDatabase.Entry entry = entries.get(succesId);
        if (entry == null) {
            entry = new SuccesDatabase.Entry();
            entries.put(succesId, entry);
        }
        return entry;
    }

    /** Vrai si l'avancement du joueur est chargé (il est connecté). */
    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    // ── Avancement ───────────────────────────────────────────────────────────

    /**
     * Annonce un fait de jeu. Tous les succès qui l'écoutent avancent.
     *
     * @param amount quantité pour un compteur cumulatif, valeur atteinte pour un
     *               compteur de record ({@link SuccesTrigger#isRecord()})
     */
    public void progress(Player player, SuccesTrigger trigger, String target, int amount) {
        if (player == null || amount <= 0) return;
        Map<String, SuccesDatabase.Entry> entries = cache.get(player.getUniqueId());
        if (entries == null) return;

        for (Succes succes : catalog.matching(trigger, target)) {
            SuccesDatabase.Entry entry = entries.get(succes.id);
            if (entry == null) {
                entry = new SuccesDatabase.Entry();
                entries.put(succes.id, entry);
            }
            if (entry.unlocked) continue;

            int before = entry.progress;
            entry.progress = trigger.isRecord()
                    ? Math.max(entry.progress, amount)
                    : entry.progress + amount;
            if (entry.progress == before) continue;

            if (entry.progress >= succes.goal) {
                entry.progress = succes.goal;
                entry.unlocked = true;
                database.save(player.getUniqueId(), succes.id, entry);
                announceUnlock(player, succes);
            } else {
                // Pas d'écriture base à chaque bloc cassé : la déconnexion et
                // la sauvegarde périodique s'en chargent.
                if (packets != null) packets.sendProgress(player, succes.id, entry);
            }
        }
    }

    /** Raccourci sans cible, pour les déclencheurs qui n'en ont pas. */
    public void progress(Player player, SuccesTrigger trigger, int amount) {
        progress(player, trigger, "", amount);
    }

    /**
     * Même chose pour un joueur peut-être absent — une vente à l'hôtel des
     * ventes conclue pendant qu'il est déconnecté, par exemple.
     *
     * <p>Connecté, on repasse par le chemin normal (cache, notification
     * immédiate). Absent, on écrit en base ; le déblocage est posé sans
     * notification, et la récompense l'attend au menu à son retour.
     */
    public void progressOffline(UUID uuid, SuccesTrigger trigger, String target, int amount) {
        if (uuid == null || amount <= 0) return;

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && isLoaded(uuid)) {
            progress(online, trigger, target, amount);
            return;
        }

        for (final Succes succes : catalog.matching(trigger, target)) {
            final UUID id = uuid;
            final int add = amount;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                @Override
                public void run() {
                    int now = database.addOffline(id, succes.id, add);
                    if (now >= succes.goal) database.markUnlockedOffline(id, succes.id);
                }
            });
        }
    }

    /**
     * Relève le temps de jeu réel des joueurs donnés et le reporte aux succès.
     *
     * <p><b>La même source que {@code /profil}</b> : le cumul persisté dans
     * {@code player_profiles.playtime_s}, plus la session en cours déduite de
     * l'heure de connexion. Sans ça, un compteur maison aurait affiché autre
     * chose que le profil pour la même notion — et il serait reparti de zéro le
     * jour de l'installation, ignorant tout le temps déjà joué.
     *
     * <p>La lecture part en asynchrone (c'est de la base), l'application
     * revient sur le thread principal (ce sont des messages et des paquets).
     * Un seul aller-retour pour tous les joueurs, pas un par joueur.
     */
    public void refreshPlaytime(Collection<UUID> players) {
        final PlayerDatabase database = plugin.getPlayerDatabase();
        if (database == null || players == null || players.isEmpty()) return;

        final List<UUID> targets = new ArrayList<>(players);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final Map<UUID, Integer> minutes = new HashMap<>();
                for (UUID uuid : targets) {
                    PlayerDatabase.PlayerProfile profile = database.getProfile(uuid);
                    long seconds = (profile == null) ? 0L : profile.playtimeSeconds;

                    // La session en cours n'est versée en base qu'à la
                    // déconnexion : sans elle, le compteur stagnerait toute la
                    // partie. Absente après un flush, elle vaut alors 0 — et le
                    // cumul persisté est à jour, donc le total reste juste.
                    Long joinTime = KsListener.getJoinTime(uuid);
                    if (joinTime != null) {
                        seconds += Math.max(0L, (System.currentTimeMillis() - joinTime) / 1000L);
                    }
                    minutes.put(uuid, (int) Math.min(Integer.MAX_VALUE, seconds / 60L));
                }

                plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        for (Map.Entry<UUID, Integer> entry : minutes.entrySet()) {
                            Player player = Bukkit.getPlayer(entry.getKey());
                            if (player != null && entry.getValue() > 0) {
                                progress(player, SuccesTrigger.PLAYTIME_MIN, "", entry.getValue());
                            }
                        }
                    }
                });
            }
        });
    }

    // ── Déblocage ────────────────────────────────────────────────────────────

    private void announceUnlock(Player player, Succes succes) {
        player.sendMessage("");
        player.sendMessage("§6§l✦ §e§lSUCCÈS DÉBLOQUÉ §8— " + succes.tierColor() + succes.name);
        player.sendMessage("  §7" + succes.description);
        player.sendMessage("  §7Récompense : " + succes.rewardText + " §8(§e/succes§8)");
        player.sendMessage("");
        try {
            player.playSound(player.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
        } catch (Throwable ignored) {
            // Nom de son absent sur un fork exotique : la notification écrite suffit.
        }
        if (packets != null) packets.sendUnlock(player, succes, entryOf(player.getUniqueId(), succes.id));
        LOG.info("[Succes] " + player.getName() + " débloque « " + succes.id + " ».");
    }

    // ── Réclamation ──────────────────────────────────────────────────────────

    /** Ce qu'a donné une tentative de réclamation. */
    public enum ClaimResult {
        OK,
        /** Succès inconnu — catalogue désynchronisé entre client et serveur. */
        UNKNOWN,
        /** Objectif pas encore atteint. */
        LOCKED,
        /** Déjà encaissé. */
        ALREADY,
        /** Pas assez de place pour les objets. */
        INVENTORY_FULL
    }

    /**
     * Verse la récompense d'un succès débloqué.
     *
     * <p>L'ordre compte : on vérifie la place AVANT de marquer l'encaissement,
     * et on ne marque qu'une fois, en base, par une écriture conditionnelle.
     * Un client qui enverrait dix réclamations n'obtiendrait qu'un versement.
     */
    public ClaimResult claim(Player player, String succesId) {
        Succes succes = catalog.get(succesId);
        if (succes == null) return ClaimResult.UNKNOWN;

        SuccesDatabase.Entry entry = entryOf(player.getUniqueId(), succesId);
        if (!entry.unlocked) return ClaimResult.LOCKED;
        if (entry.claimed) return ClaimResult.ALREADY;

        if (!hasRoomFor(player, succes.rewardItems)) return ClaimResult.INVENTORY_FULL;

        // Le drapeau d'abord : si la suite échoue, le joueur a perdu une
        // récompense — si on l'inversait, il pourrait en toucher plusieurs.
        if (!database.markClaimed(player.getUniqueId(), succesId)) {
            entry.claimed = true;
            return ClaimResult.ALREADY;
        }
        entry.claimed = true;

        if (succes.rewardMoney > 0 && economy != null) {
            economy.depositPlayer(player, succes.rewardMoney);
        }
        for (ItemStack item : succes.rewardItems) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
            for (ItemStack left : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        player.updateInventory();

        player.sendMessage("§a✔ §7Récompense de §f" + succes.name + " §7: " + succes.rewardText);
        try {
            player.playSound(player.getLocation(), Sound.ORB_PICKUP, 0.8f, 1.2f);
        } catch (Throwable ignored) {
        }
        if (packets != null) packets.sendProgress(player, succesId, entry);
        return ClaimResult.OK;
    }

    /** Réclame tout ce qui est encaissable. @return le nombre de récompenses versées. */
    public int claimAll(Player player) {
        int count = 0;
        for (Succes succes : catalog.all()) {
            SuccesDatabase.Entry entry = entryOf(player.getUniqueId(), succes.id);
            if (!entry.unlocked || entry.claimed) continue;
            if (claim(player, succes.id) == ClaimResult.OK) count++;
        }
        return count;
    }

    /** Nombre de récompenses en attente — sert au rappel affiché à la connexion. */
    public int pendingCount(UUID uuid) {
        Map<String, SuccesDatabase.Entry> entries = cache.get(uuid);
        if (entries == null) return 0;
        int count = 0;
        for (Map.Entry<String, SuccesDatabase.Entry> e : entries.entrySet()) {
            if (catalog.get(e.getKey()) == null) continue;
            if (e.getValue().unlocked && !e.getValue().claimed) count++;
        }
        return count;
    }

    public int unlockedCount(UUID uuid) {
        Map<String, SuccesDatabase.Entry> entries = cache.get(uuid);
        if (entries == null) return 0;
        int count = 0;
        for (Map.Entry<String, SuccesDatabase.Entry> e : entries.entrySet()) {
            if (catalog.get(e.getKey()) != null && e.getValue().unlocked) count++;
        }
        return count;
    }

    // ── Administration ───────────────────────────────────────────────────────

    /** Efface tout l'avancement d'un joueur. */
    public void reset(UUID uuid) {
        cache.put(uuid, new HashMap<String, SuccesDatabase.Entry>());
        database.reset(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && packets != null) packets.sendData(player);
    }

    /** Débloque un succès de force (test, dédommagement). */
    public boolean forceUnlock(Player player, String succesId) {
        Succes succes = catalog.get(succesId);
        if (succes == null) return false;
        SuccesDatabase.Entry entry = entryOf(player.getUniqueId(), succesId);
        entry.progress = succes.goal;
        entry.unlocked = true;
        database.save(player.getUniqueId(), succesId, entry);
        announceUnlock(player, succes);
        return true;
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    /**
     * Place disponible pour les objets, emplacements partiels compris.
     *
     * <p>Un simple compte d'emplacements vides refuserait la récompense d'un
     * joueur dont l'inventaire est plein mais qui a déjà 20 diamants sur une
     * pile de 64.
     */
    private boolean hasRoomFor(Player player, List<ItemStack> items) {
        if (items.isEmpty()) return true;

        // getContents() en 1.8 = les 36 emplacements du sac, sans l'armure.
        // (getStorageContents() n'existe qu'à partir de 1.9.)
        ItemStack[] contents = player.getInventory().getContents();

        // Copie de travail : simuler l'ajout évite de compter deux fois le même
        // emplacement libre pour deux objets différents.
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }

        for (ItemStack item : items) {
            int remaining = item.getAmount();
            for (int i = 0; i < copy.length && remaining > 0; i++) {
                if (copy[i] == null) {
                    int put = Math.min(remaining, item.getType().getMaxStackSize());
                    copy[i] = item.clone();
                    copy[i].setAmount(put);
                    remaining -= put;
                } else if (copy[i].isSimilar(item)) {
                    int free = copy[i].getType().getMaxStackSize() - copy[i].getAmount();
                    int put = Math.min(remaining, free);
                    copy[i].setAmount(copy[i].getAmount() + put);
                    remaining -= put;
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }
}
