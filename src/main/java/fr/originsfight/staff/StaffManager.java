package fr.originsfight.staff;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Gestionnaire en mémoire de l'état staff :
 * vanish, staffmode, freeze, mute cache, chat lock.
 */
public class StaffManager {

    private static StaffManager instance;

    public static StaffManager get() {
        if (instance == null) instance = new StaffManager();
        return instance;
    }

    // ── État Vanish ───────────────────────────────────────────────────────────
    private final Set<UUID> vanished = new HashSet<>();

    public void vanish(Player p) {
        vanished.add(p.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!isStaff(other)) other.hidePlayer(p);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        // Masquer l'item en main aux autres joueurs (paquet équipement vide)
        hideEquipmentFromOthers(p);
        p.sendMessage(StaffFormatter.PREFIX + "§aVanish active.");
    }

    public void unvanish(Player p) {
        vanished.remove(p.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(p);
        }
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.sendMessage(StaffFormatter.PREFIX + "§cVanish desactive.");
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public boolean toggleVanish(Player p) {
        if (isVanished(p.getUniqueId())) {
            unvanish(p);
            return false;
        } else {
            vanish(p);
            return true;
        }
    }

    // ── État Staff Mode ────────────────────────────────────────────────────────
    // Sauvegarde l'inventaire et le gamemode du joueur avant l'activation
    private final Set<UUID> staffMode = new HashSet<>();
    private final Map<UUID, StaffModeSnapshot> snapshots = new HashMap<>();

    public boolean isInStaffMode(UUID uuid) {
        return staffMode.contains(uuid);
    }

    public void enableStaffMode(Player p) {
        staffMode.add(p.getUniqueId());
        snapshots.put(p.getUniqueId(), new StaffModeSnapshot(
                p.getInventory().getContents().clone(),
                p.getInventory().getArmorContents().clone(),
                p.getGameMode(),
                p.getAllowFlight(),
                p.isFlying()
        ));
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        // ADVENTURE : PlayerInteractEvent fonctionne mieux qu'en CREATIVE pour les items custom
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        StaffItems.giveStaffKit(p);
        vanish(p);
        p.sendMessage(StaffFormatter.PREFIX + "§aMode Staff active. Bon modding !");
    }

    public void disableStaffMode(Player p) {
        staffMode.remove(p.getUniqueId());
        unvanish(p);
        StaffModeSnapshot snap = snapshots.remove(p.getUniqueId());
        if (snap != null) {
            p.getInventory().clear();
            p.getInventory().setContents(snap.contents);
            p.getInventory().setArmorContents(snap.armor);
            p.setGameMode(snap.gameMode);
        }
        p.sendMessage(StaffFormatter.PREFIX + "§cMode Staff desactive.");
    }

    public void toggleStaffMode(Player p) {
        if (isInStaffMode(p.getUniqueId())) {
            disableStaffMode(p);
        } else {
            enableStaffMode(p);
        }
    }

    // ── Freeze ────────────────────────────────────────────────────────────────
    private final Set<UUID> frozen = new HashSet<>();

    public void freeze(Player p) {
        frozen.add(p.getUniqueId());
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, 128, false, false));
        p.sendMessage(StaffFormatter.PREFIX + "§c§lVous avez ete freeze par le staff !");
        p.sendMessage(StaffFormatter.PREFIX + "§cNe vous deconnectez pas !");
    }

    public void unfreeze(Player p) {
        frozen.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.JUMP);
        p.sendMessage(StaffFormatter.PREFIX + "§aVous avez ete defreeze.");
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    public boolean toggleFreeze(Player p) {
        if (isFrozen(p.getUniqueId())) {
            unfreeze(p);
            return false;
        } else {
            freeze(p);
            return true;
        }
    }

    // ── Mute cache (les mutés chargés en mémoire) ─────────────────────────────
    private final Set<UUID> mutedCache = new HashSet<>();

    public void addMuted(UUID uuid) {
        mutedCache.add(uuid);
    }

    public void removeMuted(UUID uuid) {
        mutedCache.remove(uuid);
    }

    public boolean isMuted(UUID uuid) {
        return mutedCache.contains(uuid);
    }

    // ── Chat lock ─────────────────────────────────────────────────────────────
    private boolean chatLocked = false;

    public boolean isChatLocked() {
        return chatLocked;
    }

    public boolean toggleChatLock() {
        chatLocked = !chatLocked;
        return chatLocked;
    }

    /**
     * Envoie un faux paquet équipement vide aux non-staff pour que l'item en main soit invisible.
     */
    public void hideEquipmentFromOthers(Player staffPlayer) {
        try {
            String ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> packetClass = Class.forName("net.minecraft.server." + ver + ".PacketPlayOutEntityEquipment");
            Class<?> itemStackNMS = Class.forName("net.minecraft.server." + ver + ".ItemStack");
            // Construire un paquet avec un item vide pour le slot 0 (main)
            Object emptyNMS = itemStackNMS.getField("a").get(null); // ItemStack.a = AIR/empty
            Object packet = packetClass.getConstructor(int.class, int.class, itemStackNMS)
                    .newInstance(staffPlayer.getEntityId(), 0, emptyNMS);
            // Envoyer aux non-staff
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(staffPlayer) || isStaff(other)) continue;
                sendPacket(other, packet);
            }
        } catch (Exception ignored) {
            // Pas critique si ça rate — le joueur est déjà invisible
        }
    }

    private void sendPacket(Player player, Object packet) throws Exception {
        String ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object conn = handle.getClass().getField("playerConnection").get(handle);
        conn.getClass().getMethod("sendPacket",
                        Class.forName("net.minecraft.server." + ver + ".Packet"))
                .invoke(conn, packet);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    public boolean isStaff(Player p) {
        return p.isOp() || p.hasPermission("staff.staff");
    }

    /**
     * Remet les joueurs en vanish visibles pour les nouveaux joueurs qui rejoignent
     */
    public void applyVanishToNewPlayer(Player newPlayer) {
        if (isStaff(newPlayer)) return;
        for (UUID uid : vanished) {
            Player vp = Bukkit.getPlayer(uid);
            if (vp != null) newPlayer.hidePlayer(vp);
        }
    }

    // ── Snapshot inventaire staff ─────────────────────────────────────────────
    public static class StaffModeSnapshot {
        public final org.bukkit.inventory.ItemStack[] contents, armor;
        public final GameMode gameMode;
        public final boolean allowFlight, flying;

        public StaffModeSnapshot(org.bukkit.inventory.ItemStack[] contents,
                                 org.bukkit.inventory.ItemStack[] armor,
                                 GameMode gameMode, boolean allowFlight, boolean flying) {
            this.contents = contents;
            this.armor = armor;
            this.gameMode = gameMode;
            this.allowFlight = allowFlight;
            this.flying = flying;
        }
    }
}


