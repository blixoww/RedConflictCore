package fr.redconflict.essentials.service;

import fr.redconflict.core.text.Text;
import fr.redconflict.db.ItemArrayCodec;
import fr.redconflict.db.PlayerDataDatabase;
import fr.redconflict.db.PlayerLockService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Édition par le staff de l'enderchest d'un joueur ABSENT ({@code /ec <joueur>}).
 *
 * <p><b>Le danger.</b> Hors ligne, le coffre n'existe que dans {@code player_data}.
 * Si le joueur se connecte pendant qu'on l'édite, sa session charge l'ancienne
 * ligne ; à la fermeture, l'écriture du staff et la sienne s'écrasent l'une
 * l'autre — objets perdus, ou dupliqués s'ils ont été sortis du coffre.
 *
 * <p><b>La parade, en trois temps.</b>
 * <ol>
 *   <li>À l'ouverture, ce serveur prend le <b>verrou de présence</b> du joueur :
 *       les autres serveurs de la grappe le font attendre à la connexion, et ce
 *       serveur-ci la refuse (message explicite) tant que l'édition dure. S'il
 *       est déjà en ligne ailleurs, pas de verrou : on retombe sur la lecture
 *       seule.</li>
 *   <li>À la fermeture, on vérifie qu'on détient TOUJOURS le verrou avant
 *       d'écrire le seul champ {@code ender} de sa ligne, puis on le libère.</li>
 *   <li>Si le verrou a été perdu (l'attente d'un autre serveur a expiré et il a
 *       forcé la connexion), rien n'est écrit et l'édition est <b>annulée</b> :
 *       ce que le staff a sorti du coffre lui est repris, ce qu'il y a mis lui
 *       est rendu. Aucune duplication possible.</li>
 * </ol>
 */
public final class OfflineEnderEditor implements Listener {

    private static final int ENDER_SIZE = 27;

    private final PlayerDataDatabase data;
    private final String serverId;
    /** Pris à l'ouverture : le service de verrou naît avec la base, avant ou après ce module. */
    private PlayerLockService locks;

    /** Joueurs dont le coffre est en cours d'édition → session. */
    private final Map<UUID, Session> byTarget = new ConcurrentHashMap<UUID, Session>();

    public OfflineEnderEditor(PlayerDataDatabase data, String serverId) {
        this.data = data;
        this.serverId = serverId;
    }

    private static final class Session implements InventoryHolder {
        final UUID target;
        final String name;
        final UUID editor;
        final ItemStack[] original;
        Inventory inventory;

        Session(UUID target, String name, UUID editor, ItemStack[] original) {
            this.target = target;
            this.name = name;
            this.editor = editor;
            this.original = original;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    /**
     * Tente d'ouvrir le coffre en écriture.
     *
     * @return {@code true} si c'est fait ; {@code false} si le joueur est en
     *         ligne sur un autre serveur ou déjà en cours d'édition — l'appelant
     *         ouvre alors la lecture seule.
     */
    public boolean tryOpen(Player staff, UUID target, String display, PlayerDataDatabase.PlayerData snapshot) {
        fr.redconflict.RedConflictCore core = fr.redconflict.RedConflictCore.getInstance();
        // Sans synchronisation d'inventaires, le joueur ne relit jamais player_data
        // à sa connexion : écrire dedans ne changerait rien à son vrai coffre.
        if (core == null || core.getPlayerDataSync() == null) return false;
        locks = core.getPlayerLockService();
        if (locks == null) return false;
        if (byTarget.containsKey(target)) {
            staff.sendMessage(Text.error("Ce coffre est déjà en cours de modification par un autre membre du staff."));
            return false;
        }
        if (!locks.acquire(target, serverId)) return false;     // en ligne ailleurs

        ItemStack[] stored = ItemArrayCodec.decode(snapshot.ender);
        ItemStack[] original = new ItemStack[ENDER_SIZE];
        if (stored != null) {
            for (int i = 0; i < Math.min(stored.length, ENDER_SIZE); i++) {
                original[i] = stored[i] == null ? null : stored[i].clone();
            }
        }

        Session session = new Session(target, display, staff.getUniqueId(), original);
        Inventory view = Bukkit.createInventory(session, ENDER_SIZE, "EC de " + display);
        session.inventory = view;
        for (int i = 0; i < ENDER_SIZE; i++) {
            view.setItem(i, original[i] == null ? null : original[i].clone());
        }
        byTarget.put(target, session);
        staff.openInventory(view);
        return true;
    }

    // ── Fermeture : écriture ou annulation ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session)) return;
        Session session = (Session) event.getInventory().getHolder();
        if (!(event.getPlayer() instanceof Player)) return;
        finish(session, (Player) event.getPlayer());
    }

    /** Le staff se déconnecte coffre ouvert : même traitement qu'une fermeture. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        for (Session s : byTarget.values()) {
            if (s.editor.equals(event.getPlayer().getUniqueId())) finish(s, event.getPlayer());
        }
    }

    private void finish(Session session, Player staff) {
        if (byTarget.remove(session.target) == null) return;   // déjà traitée

        ItemStack[] edited = new ItemStack[ENDER_SIZE];
        for (int i = 0; i < ENDER_SIZE; i++) {
            ItemStack s = session.inventory.getItem(i);
            edited[i] = s == null || s.getType() == Material.AIR ? null : s.clone();
        }
        if (sameContents(session.original, edited)) {
            locks.release(session.target, serverId);
            return;
        }

        boolean written = locks.holds(session.target, serverId)
                && data.saveEnder(session.target, ItemArrayCodec.encode(edited));
        locks.release(session.target, serverId);

        if (written) {
            staff.sendMessage(Text.info("Enderchest de §f" + session.name + " §7enregistré."));
            return;
        }
        rollback(staff, session.original, edited);
        staff.sendMessage(Text.error("§f" + session.name + " §cs'est connecté pendant la modification :"
                + " rien n'a été enregistré, tes échanges avec son coffre ont été annulés."));
    }

    /**
     * Annule les échanges du staff : il rend ce qu'il a sorti du coffre et
     * récupère ce qu'il y a déposé. Calculé en quantités par type d'objet, peu
     * importe les cases.
     */
    private static void rollback(Player staff, ItemStack[] original, ItemStack[] edited) {
        List<ItemStack> takenOut = subtract(original, edited);   // sortis par le staff
        List<ItemStack> putIn = subtract(edited, original);      // déposés par le staff

        Inventory inv = staff.getInventory();
        for (ItemStack s : takenOut) {
            int left = s.getAmount();
            // Le curseur d'abord : l'objet peut encore y être au moment de la fermeture.
            ItemStack cursor = staff.getItemOnCursor();
            if (cursor != null && cursor.isSimilar(s)) {
                int take = Math.min(left, cursor.getAmount());
                cursor.setAmount(cursor.getAmount() - take);
                staff.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
                left -= take;
            }
            if (left > 0) {
                ItemStack rest = s.clone();
                rest.setAmount(left);
                inv.removeItem(rest);
            }
        }
        for (ItemStack s : putIn) {
            for (ItemStack overflow : inv.addItem(s.clone()).values()) {
                staff.getWorld().dropItemNaturally(staff.getLocation(), overflow);
            }
        }
        staff.updateInventory();
    }

    /** Ce que {@code a} contient de plus que {@code b}, par type d'objet. */
    private static List<ItemStack> subtract(ItemStack[] a, ItemStack[] b) {
        List<ItemStack> result = new ArrayList<ItemStack>();
        for (ItemStack s : a) {
            if (s == null) continue;
            int want = count(a, s) - count(b, s);
            if (want <= 0 || containsSimilar(result, s)) continue;
            while (want > 0) {
                ItemStack part = s.clone();
                int n = Math.min(want, s.getMaxStackSize());
                part.setAmount(n);
                result.add(part);
                want -= n;
            }
        }
        return result;
    }

    private static int count(ItemStack[] items, ItemStack like) {
        int n = 0;
        for (ItemStack s : items) if (s != null && s.isSimilar(like)) n += s.getAmount();
        return n;
    }

    private static boolean containsSimilar(List<ItemStack> list, ItemStack like) {
        for (ItemStack s : list) if (s.isSimilar(like)) return true;
        return false;
    }

    private static boolean sameContents(ItemStack[] a, ItemStack[] b) {
        for (int i = 0; i < ENDER_SIZE; i++) {
            ItemStack x = a[i], y = b[i];
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    /**
     * Jeter un objet pendant l'édition le ferait échapper à l'annulation : il
     * resterait au sol ET dans le coffre si l'écriture échoue.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        for (Session s : byTarget.values()) {
            if (s.editor.equals(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ── Connexion bloquée pendant l'édition ─────────────────────────────────

    /**
     * Sur CE serveur, le verrou est déjà à nous : il ne ferait pas attendre le
     * joueur. On refuse donc explicitement sa connexion le temps de l'édition.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (byTarget.containsKey(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§cTon enderchest est en cours de vérification par le staff.\n"
                  + "§7Reconnecte-toi dans quelques instants.");
        }
    }

    /** Arrêt du serveur coffre ouvert : on libère les verrous, sans écrire. */
    public void closeAll() {
        for (Session s : byTarget.values()) {
            Player staff = Bukkit.getPlayer(s.editor);
            if (staff != null) staff.closeInventory();   // passe par finish()
        }
        if (locks != null) for (UUID target : byTarget.keySet()) locks.release(target, serverId);
        byTarget.clear();
    }
}
