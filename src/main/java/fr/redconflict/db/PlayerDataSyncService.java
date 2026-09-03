package fr.redconflict.db;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Service de synchronisation cross-serveur de l'état joueur : inventaire + armure + slot tenu,
 * enderchest, XP/niveaux, vie, faim/saturation et effets de potion.
 *
 * <p>Toutes les méthodes touchent l'API Bukkit (inventaires, attributs) et DOIVENT être appelées sur
 * le thread principal (join/quit/disable le sont). La sauvegarde au quit est synchrone afin qu'elle
 * soit persistée AVANT la libération du verrou (et donc avant que le joueur n'arrive sur un autre
 * serveur).
 */
public class PlayerDataSyncService {

    private static final Logger LOG = Logger.getLogger("PlayerDataSync");

    private final PlayerDataDatabase dataDb;

    public PlayerDataSyncService(PlayerDataDatabase dataDb) {
        this.dataDb = dataDb;
    }

    /** Construit un instantané complet de l'état du joueur (à appeler sur le thread principal). */
    public static PlayerDataDatabase.PlayerData snapshot(Player p) {
        PlayerInventory inv = p.getInventory();
        PlayerDataDatabase.PlayerData d = new PlayerDataDatabase.PlayerData();
        d.invMain     = ItemArrayCodec.encode(inv.getContents());
        d.invArmor    = ItemArrayCodec.encode(inv.getArmorContents());
        d.heldSlot    = inv.getHeldItemSlot();
        d.ender       = ItemArrayCodec.encode(p.getEnderChest().getContents());
        d.expLevel    = p.getLevel();
        d.expProgress = p.getExp();
        d.health      = p.getHealth();
        d.food        = p.getFoodLevel();
        d.saturation  = p.getSaturation();
        d.effects     = PotionEffectCodec.encode(p.getActivePotionEffects());
        d.rings       = ringSlots(p);
        return d;
    }

    /**
     * Les anneaux du joueur, encodés — ou {@code null} si on ne peut pas répondre.
     *
     * <p>Les anneaux vivent dans leur propre module et dans leurs propres
     * fichiers, mais ils font partie de ce que le joueur emporte : sans eux,
     * passer sur le Minage revenait à arriver les doigts nus, avec les anneaux
     * du serveur d'en face. Ils voyagent donc avec l'inventaire, par la même
     * table et le même relais.
     *
     * <p>Le module peut être absent (serveur sans anneaux) ou le joueur déjà
     * déchargé : dans les deux cas on renvoie {@code null}, qui signifie « ne
     * touche pas à ce qui est en base » et surtout pas « huit slots vides ».
     */
    private static byte[] ringSlots(Player p) {
        try {
            fr.redconflict.ring.RingManager rings = fr.redconflict.ring.RingEffects.getManager();
            if (rings == null) {
                return null;
            }
            org.bukkit.inventory.ItemStack[] slots = rings.snapshotSlots(p.getUniqueId());
            return slots == null ? null : ItemArrayCodec.encode(slots);
        } catch (Throwable ignored) {
            return null; // module anneaux absent ou non initialisé
        }
    }

    /** Encode et sauvegarde l'état complet du joueur. */
    public void saveNow(Player p) {
        dataDb.save(p.getUniqueId(), snapshot(p));
    }

    /**
     * Charge l'état du joueur depuis H2 et l'applique. Si aucune donnée n'existe (premier passage),
     * l'état local est conservé et enregistré comme base.
     *
     * @return {@code true} si des données existantes ont été appliquées, {@code false} si premier passage.
     */
    public boolean loadAndApply(Player p) {
        UUID uuid = p.getUniqueId();
        PlayerDataDatabase.PlayerData d = dataDb.load(uuid);
        if (d == null) {
            // Premier passage : on conserve l'état local et on l'enregistre comme base.
            saveNow(p);
            return false;
        }

        // ── Inventaire + enderchest ──────────────────────────────────────────────
        PlayerInventory inv = p.getInventory();
        inv.setContents(normalize(ItemArrayCodec.decode(d.invMain), inv.getSize()));
        inv.setArmorContents(normalize(ItemArrayCodec.decode(d.invArmor), 4));
        p.getEnderChest().setContents(normalize(ItemArrayCodec.decode(d.ender), p.getEnderChest().getSize()));
        inv.setHeldItemSlot(Math.max(0, Math.min(8, d.heldSlot)));

        // ── XP / niveaux ─────────────────────────────────────────────────────────
        p.setLevel(Math.max(0, d.expLevel));
        p.setExp(clamp01(d.expProgress));

        // ── Faim / saturation ────────────────────────────────────────────────────
        p.setFoodLevel(Math.max(0, Math.min(20, d.food)));
        p.setSaturation(Math.max(0f, d.saturation));

        // ── Vie (bornée à la vie max courante ; valeur invalide → vie pleine) ─────
        double max = p.getMaxHealth();
        double health = (d.health > 0 && !Double.isNaN(d.health)) ? Math.min(d.health, max) : max;
        p.setHealth(health);

        // ── Effets de potion (on purge les effets locaux avant d'appliquer ceux du save) ──
        for (PotionEffect cur : p.getActivePotionEffects()) {
            p.removePotionEffect(cur.getType());
        }
        for (PotionEffect e : PotionEffectCodec.decode(d.effects)) {
            p.addPotionEffect(e, true);
        }

        // ── Anneaux ────────────────────────────────────────────────────────────────
        // Une colonne nulle vient d'une ligne écrite avant l'ajout de la
        // colonne : on garde alors les anneaux locaux plutôt que de les effacer.
        applyRings(p, d.rings);

        p.updateInventory();
        return true;
    }

    /** Pose les anneaux reçus, et écrit le fichier local pour que le module les relise. */
    private static void applyRings(Player p, byte[] encoded) {
        if (encoded == null) {
            return;
        }
        try {
            fr.redconflict.ring.RingManager rings = fr.redconflict.ring.RingEffects.getManager();
            if (rings == null) {
                return;
            }
            rings.setSlots(p.getUniqueId(), normalize(
                    ItemArrayCodec.decode(encoded), fr.redconflict.ring.RingManager.RING_SIZE));
        } catch (Throwable ignored) {
            // Module anneaux absent : l'inventaire, lui, est déjà appliqué.
        }
    }

    /**
     * Démarre la sauvegarde automatique périodique des joueurs connectés.
     *
     * <p>Sécurité anti-crash : borne la perte de données à {@code periodMinutes}. L'instantané est pris
     * sur le thread principal (API Bukkit), puis l'écriture en base part en asynchrone → aucun impact
     * de lag pour les joueurs.
     *
     * @param periodMinutes intervalle en minutes ; ≤ 0 désactive l'auto-save.
     */
    public void startAutoSave(Plugin plugin, int periodMinutes) {
        if (periodMinutes <= 0) return;
        long ticks = periodMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                // Instantané sur le thread principal.
                final UUID id = p.getUniqueId();
                final PlayerDataDatabase.PlayerData d = snapshot(p);
                // Écriture base en asynchrone (Hikari est thread-safe).
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dataDb.save(id, d));
            }
        }, ticks, ticks);
    }

    /** Sauvegarde tous les joueurs connectés (appelé au onDisable). */
    public void saveAll(Iterable<? extends Player> players) {
        for (Player p : players) {
            try { saveNow(p); } catch (Exception e) { LOG.warning("[Sync] saveAll(" + p.getName() + "): " + e.getMessage()); }
        }
    }

    /** Ajuste un tableau d'items à la taille exacte d'un inventaire (copie tronquée/complétée). */
    private static ItemStack[] normalize(ItemStack[] src, int size) {
        ItemStack[] out = new ItemStack[size];
        if (src != null) {
            int n = Math.min(src.length, size);
            System.arraycopy(src, 0, out, 0, n);
        }
        return out;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 0.9999f) return 0.9999f; // setExp(1.0f) lève une exception sur certaines versions
        return v;
    }
}
