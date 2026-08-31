package fr.redconflict.staff;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Listener principal du systeme staff.
 *
 * UTILISATION DES ITEMS :
 *   - Boussole   : clic droit dans le vide -> TP vers un joueur aleatoire
 *   - Glace      : clic droit SUR un joueur -> freeze/defreeze
 *   - Oeil       : clic droit SUR un joueur -> spectate
 *   - Papier     : clic droit SUR un joueur -> voir ses sanctions
 *   - Livre      : clic droit dans le vide  -> toggle staff chat
 *   - TNT        : clic droit dans le vide  -> quitter le staffmode
 */
public class StaffListener implements Listener {

    private final StaffManager mgr = StaffManager.get();
    private final StaffDatabase db;
    private final RedConflictCore plugin;

    private final Set<UUID> staffChatOnly = new HashSet<>();
    private final Map<UUID, Location> previousLocations = new HashMap<>();

    public StaffListener(StaffDatabase db, RedConflictCore plugin) {
        this.db = db;
        this.plugin = plugin;
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        Player p = event.getPlayer();
        String uuid = p.getUniqueId().toString();
        String ip   = event.getAddress().getHostAddress();
        db.saveIp(uuid, p.getName(), ip);

        StaffDatabase.Sanction ban = db.getActiveSanction(uuid, StaffDatabase.SanctionType.BAN);
        if (ban != null && !ban.isExpired()) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    StaffFormatter.banScreen(ban.reason,
                            ban.isPermanent() ? "Permanent" : StaffFormatter.formatDate(ban.expiresAt),
                            ban.staff));
            return;
        }
        for (String linked : db.getUuidsByIp(ip)) {
            if (linked.equals(uuid)) continue;
            StaffDatabase.Sanction lb = db.getActiveSanction(linked, StaffDatabase.SanctionType.BAN);
            if (lb != null && !lb.isExpired()) {
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                        StaffFormatter.banScreen("[IP-BAN] " + lb.reason,
                                lb.isPermanent() ? "Permanent" : StaffFormatter.formatDate(lb.expiresAt),
                                lb.staff));
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        mgr.applyVanishToNewPlayer(p);
        StaffDatabase.Sanction mute = db.getActiveSanction(p.getUniqueId().toString(), StaffDatabase.SanctionType.MUTE);
        if (mute != null && !mute.isExpired()) {
            mgr.addMuted(p.getUniqueId());
            p.sendMessage(StaffFormatter.muteMessage(mute.reason,
                    mute.isPermanent() ? "Permanent" : StaffFormatter.formatDate(mute.expiresAt)));
        }
        if (mgr.isStaff(p)) {
            long warns = db.getHistory(p.getUniqueId().toString()).stream()
                    .filter(s -> s.type == StaffDatabase.SanctionType.WARN && s.active).count();
            if (warns > 0)
                p.sendMessage(StaffFormatter.PREFIX + "§eVous avez §c" + warns + " §eavertissement(s) actif(s). /sanctions " + p.getName());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (mgr.isInStaffMode(p.getUniqueId())) mgr.disableStaffMode(p);
        if (mgr.isFrozen(p.getUniqueId()))
            broadcastStaff(StaffFormatter.PREFIX + "§c[!] §f" + p.getName() + " §cs'est deconnecte en etant FREEZE !");
        previousLocations.remove(p.getUniqueId());
        staffChatOnly.remove(p.getUniqueId());
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (mgr.isChatLocked() && !mgr.isStaff(p)) {
            event.setCancelled(true);
            p.sendMessage(StaffFormatter.PREFIX + "§cLe chat est verrouille par le staff.");
            return;
        }
        if (mgr.isMuted(p.getUniqueId())) {
            event.setCancelled(true);
            StaffDatabase.Sanction mute = db.getActiveSanction(p.getUniqueId().toString(), StaffDatabase.SanctionType.MUTE);
            if (mute != null)
                p.sendMessage(StaffFormatter.muteMessage(mute.reason,
                        mute.isPermanent() ? "Permanent" : StaffFormatter.formatDate(mute.expiresAt)));
            return;
        }
        if (staffChatOnly.contains(p.getUniqueId())) {
            event.setCancelled(true);
            broadcastStaffChat(p, event.getMessage());
            return;
        }
        event.getRecipients().removeIf(r -> staffChatOnly.contains(r.getUniqueId()) && !mgr.isStaff(r));
    }

    // ── Freeze ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrozenMove(PlayerMoveEvent event) {
        if (!mgr.isFrozen(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()))
            event.setTo(from.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrozenCommand(PlayerCommandPreprocessEvent event) {
        if (!mgr.isFrozen(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(StaffFormatter.PREFIX + "§cImpossible d'utiliser une commande en etant freeze !");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrozenDrop(PlayerDropItemEvent event) {
        if (mgr.isFrozen(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrozenInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!mgr.isFrozen(p.getUniqueId())) return;
        if (StaffItems.isAnyStaffItem(p.getItemInHand())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrozenHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player && mgr.isFrozen(event.getEntity().getUniqueId()))
            event.setCancelled(true);
    }

    // ── Quitter le spectate en sneakant ──────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player p = event.getPlayer();
        if (!event.isSneaking()) return;                        // seulement au shift-down
        if (!mgr.isInStaffMode(p.getUniqueId())) return;       // seulement en staffmode
        if (p.getGameMode() != GameMode.SPECTATOR) return;      // seulement si spectateur
        doExitSpectate(p);
    }

    // ── Items staff : clic droit SUR un joueur ────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player staff = event.getPlayer();
        if (!mgr.isInStaffMode(staff.getUniqueId())) return;
        if (!(event.getRightClicked() instanceof Player)) return;

        Player target = (Player) event.getRightClicked();
        if (target.equals(staff)) return;

        event.setCancelled(true);
        ItemStack item = staff.getItemInHand();
        if (item == null || item.getType() == Material.AIR) return;

        if (StaffItems.isStaffItem(item, StaffItems.FREEZE_NAME)) {
            doFreeze(staff, target);
        } else if (StaffItems.isStaffItem(item, StaffItems.STATS_NAME)) {
            doStats(staff, target);
        }
    }

    // ── Items staff : clic droit dans le vide (boussole, livre, TNT) ──────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!mgr.isInStaffMode(p.getUniqueId())) return;

        org.bukkit.event.block.Action action = event.getAction();
        boolean isRightClick = action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                            || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
        if (!isRightClick) return;

        // Conteneur : consultation silencieuse, avant tout usage d'item staff.
        // Passe en premier pour que le contenu d'un coffre reste consultable
        // quelle que soit la boussole/glace tenue en main.
        if (action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && openSilently(p, event.getClickedBlock())) {
            event.setCancelled(true);
            return;
        }

        ItemStack item = p.getItemInHand();
        if (item == null || item.getType() == Material.AIR) return;

        event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
        event.setCancelled(true);

        if (StaffItems.isStaffItem(item, StaffItems.COMPASS_NAME)) {
            doRandomTp(p);
        } else if (StaffItems.isStaffItem(item, StaffItems.STAFFCHAT_NAME)) {
            toggleStaffChatOnly(p);
        } else if (StaffItems.isStaffItem(item, StaffItems.SUSPECT_MINAGE)) {
            Bukkit.dispatchCommand(p, "topluck");
        } else if (StaffItems.isStaffItem(item, StaffItems.EXIT_NAME)) {
            mgr.disableStaffMode(p);

        }
    }

    // ── Actions items ─────────────────────────────────────────────────────────

    private void doRandomTp(final Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(staff) && !mgr.isVanished(online.getUniqueId())
                    && !mgr.isInStaffMode(online.getUniqueId())) {
                candidates.add(online);
            }
        }
        if (candidates.isEmpty()) {
            staff.sendMessage(StaffFormatter.PREFIX + "§cAucun joueur disponible pour la teleportation.");
            return;
        }
        final Player target = candidates.get(new Random().nextInt(candidates.size()));
        previousLocations.put(staff.getUniqueId(), staff.getLocation().clone());
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                staff.teleport(target.getLocation());
                staff.sendMessage(StaffFormatter.PREFIX + "§aTeleporte vers §f" + target.getName()
                        + " §8| §7" + formatLoc(target.getLocation()));
            }
        });
    }

    /** Quitter le mode spectateur sans quitter le staffmode. */
    private void doExitSpectate(final Player staff) {
        if (staff.getGameMode() != GameMode.SPECTATOR) {
            staff.sendMessage(StaffFormatter.PREFIX + "§7Vous n'etes pas en mode spectateur.");
            return;
        }
        final Location prev = previousLocations.remove(staff.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                staff.setGameMode(GameMode.ADVENTURE);
                staff.setAllowFlight(true);
                staff.setFlying(true);
                if (prev != null) {
                    staff.teleport(prev);
                    staff.sendMessage(StaffFormatter.PREFIX + "§9Retour a votre position precedente.");
                } else {
                    staff.sendMessage(StaffFormatter.PREFIX + "§9Mode spectateur quitte.");
                }
            }
        });
    }

    private void doFreeze(final Player staff, final Player target) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                boolean fr = mgr.toggleFreeze(target);
                if (fr) {
                    for (String line : StaffFormatter.freezeLines(staff.getName()))
                        target.sendMessage(line);
                    broadcastStaff(StaffFormatter.PREFIX + "§3[Freeze] §f" + staff.getName() + " §7a freeze §f" + target.getName());
                } else {
                    target.sendMessage(StaffFormatter.PREFIX + "§aVous avez ete defreeze par §f" + staff.getName());
                    broadcastStaff(StaffFormatter.PREFIX + "§a[Defreeze] §f" + staff.getName() + " §7a defreeze §f" + target.getName());
                }
            }
        });
    }

    private void doInspect(Player staff, Player target) {
        List<StaffDatabase.Sanction> history = db.getHistory(target.getUniqueId().toString());
        StaffFormatter.sendHistoryHeader(staff, target.getName(), history.size());
        if (history.isEmpty()) {
            staff.sendMessage("  §7Aucune sanction enregistree.");
        } else {
            int shown = Math.min(history.size(), 5);
            for (int i = 0; i < shown; i++)
                StaffFormatter.sendHistoryEntry(staff, history.get(i));
            if (history.size() > 5)
                staff.sendMessage("§8> §7Suite : §f/sanctions " + target.getName() + " 2");
        }
    }

    /** Affiche les statistiques de minage détaillées d'un joueur ciblé en chat staff. */
    private void doStats(final Player staff, final Player target) {
        final String uuid = target.getUniqueId().toString();
        final String name = target.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            public void run() {
                final long emerald = db.getPlayerBlockCount(uuid, "EMERALD_ORE");
                final long ruby    = db.getPlayerBlockCount(uuid, "RUBY_ORE");
                final long cobalt  = db.getPlayerBlockCount(uuid, "COBALT_ORE");
                final long stone   = db.getPlayerBlockCount(uuid, "STONE")
                                   + db.getPlayerBlockCount(uuid, "COBBLESTONE");
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    public void run() {
                        long modded = emerald + ruby + cobalt;
                        // Calcul du ratio et couleur associée
                        String ratioStr;
                        if (stone <= 0) {
                            ratioStr = "§8Non mesurable §7(pas de stone trackee)";
                        } else {
                            double ratio = (double) modded / stone;
                            long stonePerModded = modded > 0 ? stone / modded : stone;
                            boolean suspect = modded >= 3 && ratio > (1.0 / 30.0);
                            String col;
                            if      (ratio > 1.0 / 10.0) col = "§4§l";
                            else if (ratio > 1.0 / 20.0) col = "§c";
                            else if (ratio > 1.0 / 30.0) col = "§6";
                            else                          col = "§a";
                            ratioStr = col + "1 moddé / " + stonePerModded + " stone"
                                     + " §8(" + String.format("%.4f", ratio) + ")"
                                     + (suspect ? " §c§l[SUSPECT]" : "");
                        }
                        String sep = "§8§m                                   ";
                        staff.sendMessage(sep);
                        staff.sendMessage("  §e§lStats Minage §8| §f" + name);
                        staff.sendMessage(sep);
                        staff.sendMessage("  §a Émeraude  §8» §f" + emerald + " blocs");
                        staff.sendMessage("  §c Ruby      §8» §f" + ruby    + " blocs");
                        staff.sendMessage("  §b Cobalt    §8» §f" + cobalt  + " blocs");
                        staff.sendMessage("  §7 Stone     §8» §f" + stone   + " blocs");
                        staff.sendMessage(sep);
                        staff.sendMessage("  §7 Total moddé §8» §e" + modded + " blocs");
                        staff.sendMessage("  §7 Ratio      §8» " + ratioStr);
                        staff.sendMessage(sep);
                    }
                });
            }
        });
    }

    // ── Toggle staff chat ──────────────────────────────────────────────────────

    public void toggleStaffChatOnly(Player p) {
        if (staffChatOnly.contains(p.getUniqueId())) {
            staffChatOnly.remove(p.getUniqueId());
            p.sendMessage(StaffFormatter.PREFIX + "§7Chat public.");
        } else {
            staffChatOnly.add(p.getUniqueId());
            p.sendMessage(StaffFormatter.PREFIX + "§aMode §lStaff Chat §aactif §8.");
        }
    }

    // ── Protection items staff ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStaffItemDrop(PlayerDropItemEvent event) {
        if (StaffItems.isAnyStaffItem(event.getItemDrop().getItemStack()))
            event.setCancelled(true);
    }

    /**
     * En staffmode, aucun item ne bouge : ni depuis un coffre consulté, ni depuis
     * le kit staff, ni vers le sol.
     *
     * <p>On annule tous les clics plutôt que les seuls items staff. Un modérateur
     * en vanish n'a aucune raison de déplacer un item, et l'inventaire réel du
     * joueur est de toute façon rendu à la sortie du mode : le moindre item
     * ramassé entre-temps serait perdu par {@code disableStaffMode}, qui réécrit
     * le contenu depuis l'instantané.
     *
     * <p>Les menus du plugin continuent de fonctionner : ils s'annulent déjà
     * eux-mêmes et lisent le clic sans tenir compte de son annulation.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStaffInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        if (mgr.isInStaffMode(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStaffInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        if (mgr.isInStaffMode(p.getUniqueId())) event.setCancelled(true);
    }

    /** Marcher sur un drop ne le ramasse pas : le loot reste au sol pour son propriétaire. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStaffPickup(PlayerPickupItemEvent event) {
        if (mgr.isInStaffMode(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    // ── Consultation silencieuse des conteneurs ───────────────────────────────

    /**
     * Ouvre une copie du conteneur au lieu du conteneur lui-même.
     *
     * <p>Ouvrir le vrai inventaire incrémente le compteur de spectateurs de la
     * tuile, ce qui déclenche l'animation du couvercle et son bruit pour tout le
     * monde autour : un modérateur en vanish se trahissait à chaque coffre
     * ouvert. La copie n'existe que pour lui, et rien n'y est écrit en retour —
     * ce que le staff voit est un instantané, jamais une prise.
     *
     * @return false si le bloc n'est pas un conteneur, pour laisser passer le clic
     */
    private boolean openSilently(Player staff, org.bukkit.block.Block block) {
        Inventory source;
        String title;

        if (block.getType() == Material.ENDER_CHEST) {
            // Le coffre de l'End est propre au joueur : celui du staff, donc.
            // On l'ouvre quand même en copie, seulement pour ne pas animer le bloc.
            source = staff.getEnderChest();
            title = "§8Coffre de l'End §7(lecture)";
        } else {
            org.bukkit.block.BlockState state = block.getState();
            if (!(state instanceof InventoryHolder)) return false;
            try {
                source = ((InventoryHolder) state).getInventory();
            } catch (Exception e) {
                return false;
            }
            title = containerTitle(block.getType());
        }
        if (source == null) return false;

        int size = source.getSize();
        Inventory copy = (size > 0 && size <= 54 && size % 9 == 0)
                ? Bukkit.createInventory(null, size, title)
                : Bukkit.createInventory(null, source.getType(), title);

        ItemStack[] contents = source.getContents();
        int n = Math.min(contents.length, copy.getSize());
        ItemStack[] cloned = new ItemStack[n];
        for (int i = 0; i < n; i++) cloned[i] = contents[i] == null ? null : contents[i].clone();
        copy.setContents(cloned);

        staff.openInventory(copy);
        return true;
    }

    /** Titre de la fenêtre — 32 caractères maximum côté client 1.8. */
    private static String containerTitle(Material type) {
        switch (type) {
            case CHEST:          return "§8Coffre §7(lecture)";
            case TRAPPED_CHEST:  return "§8Coffre piégé §7(lecture)";
            case FURNACE:
            case BURNING_FURNACE: return "§8Four §7(lecture)";
            case DISPENSER:      return "§8Distributeur §7(lecture)";
            case DROPPER:        return "§8Dropper §7(lecture)";
            case HOPPER:         return "§8Entonnoir §7(lecture)";
            case BREWING_STAND:  return "§8Alambic §7(lecture)";
            default:             return "§8Conteneur §7(lecture)";
        }
    }

    // ── TopLuck ───────────────────────────────────────────────────────────────

    // Minerais moddés (indicateur d'xray) + stone/cobble (référence de minage normal)
    // Le ratio modded/stone révèle l'xray : peu de stone cassée pour bcp de minerais = suspect
    private static final Set<String> TRACKED_BLOCKS = new HashSet<>(Arrays.asList(
        "EMERALD_ORE", "RUBY_ORE", "COBALT_ORE",  // minerais à tracker
        "STONE", "COBBLESTONE"                      // référence : stone cassée normalement
    ));

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        String block = event.getBlock().getType().name().toUpperCase();
        if (!TRACKED_BLOCKS.contains(block)) return;
        Player p = event.getPlayer();
        db.incrementBlock(p.getUniqueId().toString(), p.getName(), block);
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    public void broadcastStaff(String msg) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (mgr.isStaff(p)) p.sendMessage(msg);
    }

    public void broadcastStaffChat(Player sender, String msg) {
        broadcastStaffChat(sender, sender != null ? sender.getName() : "Console", msg);
    }

    public void broadcastStaffChat(Player sender, String name, String msg) {
        String formatted = "§8[§6§lSC§8] §c" + name + " §8| §f" + msg;
        for (Player p : Bukkit.getOnlinePlayers())
            if (mgr.isStaff(p)) p.sendMessage(formatted);
        Bukkit.getLogger().info("[StaffChat] " + name + ": " + msg);
    }

    private String formatLoc(Location loc) {
        return "X:" + loc.getBlockX() + " Y:" + loc.getBlockY() + " Z:" + loc.getBlockZ();
    }

}

