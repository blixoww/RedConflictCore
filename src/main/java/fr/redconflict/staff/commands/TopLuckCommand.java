package fr.redconflict.staff.commands;

import fr.redconflict.core.command.CoreCommand;
import fr.redconflict.staff.StaffDatabase;
import fr.redconflict.staff.StaffFormatter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * /topluck — Classement "Suspect Minage" des joueurs.
 *
 * Indicateur principal : ratio minerais moddés / stone cassée.
 * Un mineur normal casse beaucoup de stone pour trouver peu de minerais.
 * Un xrayeur a un ratio élevé (bcp de minerais pour peu de stone).
 *
 * Seuil suspect : > 1 minerai moddé pour 30 stone (ratio > 0.033)
 *                 avec au moins SUSPECT_MIN minerais moddés minés.
 *
 * Affichage : PAPER (pas de skull NMS) — tri par ratio décroissant.
 */
public class TopLuckCommand extends CoreCommand implements Listener {

    // ── Configuration suspect ─────────────────────────────────────────────────
    /** Minimum de minerais moddés pour être affiché (filtre les joueurs avec 1 seul bloc) */
    private static final int    SUSPECT_MIN_MODDED = 3;
    /** Ratio moddé/stone au-delà duquel on considère SUSPECT (1 minerai / 30 stone = 0.0333) */
    private static final double SUSPECT_THRESHOLD  = 1.0 / 30.0;

    private static final int    SLOTS_PER_PAGE = 45;
    private static final String TITLE_PREFIX   = "SuspectMinage|";

    private final StaffDatabase db;

    public TopLuckCommand(JavaPlugin plugin, StaffDatabase db) {
        super(plugin, "topluck", true);
        this.db     = db;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        openPage((Player) sender, 1);
    }

    // ── Chargement asynchrone ─────────────────────────────────────────────────

    public void openPage(final Player player, final int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            public void run() {
                // Récupérer et trier par ratio décroissant (les plus suspects en premier)
                final List<StaffDatabase.LuckData> raw = db.getAllLuckData();
                final List<StaffDatabase.LuckData> data = sortByRatio(raw);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    public void run() { showPage(player, data, page); }
                });
            }
        });
    }

    /** Trie par ratio modded/stone décroissant (les suspects en premier).
     *  Les joueurs sans stone (ratio=-1) vont en fin de liste. */
    private List<StaffDatabase.LuckData> sortByRatio(List<StaffDatabase.LuckData> raw) {
        List<StaffDatabase.LuckData> sorted = new ArrayList<>(raw);
        Collections.sort(sorted, new Comparator<StaffDatabase.LuckData>() {
            public int compare(StaffDatabase.LuckData a, StaffDatabase.LuckData b) {
                double ra = a.ratio() < 0 ? Double.MAX_VALUE : a.ratio();
                double rb = b.ratio() < 0 ? Double.MAX_VALUE : b.ratio();
                // ratio positif élevé = plus suspect = en premier
                // ratio négatif (pas de stone) = sans référence = en dernier
                if (a.ratio() < 0 && b.ratio() < 0) return Long.compare(b.modded, a.modded);
                if (a.ratio() < 0) return 1;
                if (b.ratio() < 0) return -1;
                return Double.compare(rb, ra); // décroissant
            }
        });
        return sorted;
    }

    // ── Affichage de la page ──────────────────────────────────────────────────

    private void showPage(Player player, List<StaffDatabase.LuckData> data, int page) {
        if (data.isEmpty()) {
            player.sendMessage(StaffFormatter.PREFIX + "§7Aucun minage enregistré pour l'instant.");
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) data.size() / SLOTS_PER_PAGE));
        page = Math.max(1, Math.min(page, totalPages));

        String title = TITLE_PREFIX + page + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(null, 54, title);

        int from = (page - 1) * SLOTS_PER_PAGE;
        int to   = Math.min(from + SLOTS_PER_PAGE, data.size());

        for (int i = from; i < to; i++) {
            inv.setItem(i - from, buildPaper(data.get(i), i + 1));
        }

        // Ligne 6 : navigation
        for (int s = 45; s < 54; s++) inv.setItem(s, makeGlass());
        if (page > 1)
            inv.setItem(45, makeArrow("§7< Page précédente", page - 1));
        inv.setItem(49, makeCenterInfo(page, totalPages, data.size()));
        if (page < totalPages)
            inv.setItem(53, makeArrow("§7> Page suivante", page + 1));

        player.openInventory(inv);
    }

    // ── Construction d'un item PAPER par joueur ───────────────────────────────

    private ItemStack buildPaper(StaffDatabase.LuckData d, int rank) {
        boolean suspect = d.isSuspect(SUSPECT_MIN_MODDED);
        double  ratio   = d.ratio();

        // Choisir le matériau selon le niveau de suspicion
        // BOOK_AND_QUILL = très suspect, PAPER = normal, BOOK = sans référence stone
        Material mat;
        if (ratio < 0)              mat = Material.BOOK;          // pas de stone = pas de référence
        else if (suspect)           mat = Material.BOOK_AND_QUILL; // suspect
        else                        mat = Material.PAPER;          // normal

        ItemStack item = new ItemStack(mat, 1);
        ItemMeta  meta = item.getItemMeta();

        // ── Nom ──────────────────────────────────────────────────────────────
        String rankColor;
        if      (rank == 1) rankColor = "§6§l";
        else if (rank == 2) rankColor = "§f§l";
        else if (rank == 3) rankColor = "§c§l";
        else                rankColor = "§7";

        String suspectTag = suspect ? " §c§l[SUSPECT]" : "";
        meta.setDisplayName(rankColor + "#" + rank + " §r§f" + d.name + suspectTag);

        // ── Lore ─────────────────────────────────────────────────────────────
        String pctEm = pct(d.emerald, d.modded);
        String pctRb = pct(d.ruby,    d.modded);
        String pctCo = pct(d.cobalt,  d.modded);

        // Formatage du ratio moddé/stone
        String ratioStr;
        String ratioColor;
        if (ratio < 0) {
            ratioStr   = "§8Pas de référence stone";
            ratioColor = "§8";
        } else {
            // Exprimer comme "1 moddé / X stone"
            long stonePerModded = d.modded > 0 ? (d.stone / d.modded) : 0;
            String ratioFmt = String.format("%.4f", ratio);
            ratioStr = "§f1 §7moddé pour §f" + stonePerModded + " §7stone §8(x" + ratioFmt + ")";
            if      (ratio > 1.0 / 10.0)  ratioColor = "§4§l"; // 1 moddé / 10 stone  = très suspect
            else if (ratio > 1.0 / 20.0)  ratioColor = "§c";   // 1 moddé / 20 stone  = suspect
            else if (ratio > 1.0 / 30.0)  ratioColor = "§6";   // 1 moddé / 30 stone  = légèrement suspect
            else                           ratioColor = "§a";   // normal
        }

        List<String> lore = new ArrayList<>();
        lore.add("§8▬▬▬▬▬▬▬▬ Minerais rares ▬▬▬▬▬▬▬▬");
        lore.add("§a  Émeraude  §8» §f" + d.emerald + " §8(§7" + pctEm + "% des moddés§8)");
        lore.add("§c  Ruby      §8» §f" + d.ruby    + " §8(§7" + pctRb + "% des moddés§8)");
        lore.add("§b  Cobalt    §8» §f" + d.cobalt  + " §8(§7" + pctCo + "% des moddés§8)");
        lore.add("§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        lore.add("§7  Total moddé   §8» §e" + d.modded + " blocs");
        lore.add("§7  Stone cassée  §8» §f" + (d.stone > 0 ? d.stone : "§8Non mesuré"));
        lore.add("");
        lore.add("§7  Ratio moddé§8/§7stone §8:");
        lore.add("  " + ratioColor + ratioStr);
        if (suspect) {
            lore.add("");
            lore.add("§c§l  ⚠ SUSPECT — ratio anormal !");
            lore.add("§c  Vérifiez les logs de ce joueur.");
        } else if (ratio < 0) {
            lore.add("");
            lore.add("§8  Pas de stone trackée — impossible");
            lore.add("§8  d'évaluer le comportement.");
        }
        lore.add("");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── Listener GUI ──────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory inv = event.getInventory();
        if (inv == null || inv.getTitle() == null) return;
        if (!inv.getTitle().startsWith(TITLE_PREFIX)) return;

        event.setCancelled(true);

        int currentPage = 1;
        try {
            String[] parts = inv.getTitle().substring(TITLE_PREFIX.length()).split("/");
            currentPage = Integer.parseInt(parts[0].trim());
        } catch (Exception ignored) {}

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        final Player player = (Player) event.getWhoClicked();

        if (slot == 45 && currentPage > 1) {
            final int prev = currentPage - 1;
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                public void run() { openPage(player, prev); }
            });
        } else if (slot == 53) {
            final int next = currentPage + 1;
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                public void run() { openPage(player, next); }
            });
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private String pct(long val, long total) {
        if (total == 0) return "0.0";
        return String.format("%.1f", (double) val / total * 100.0);
    }

    private ItemStack makeGlass() {
        ItemStack g = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7);
        ItemMeta m = g.getItemMeta();
        m.setDisplayName(" ");
        g.setItemMeta(m);
        return g;
    }

    private ItemStack makeArrow(String name, int targetPage) {
        ItemStack a = new ItemStack(Material.ARROW, 1);
        ItemMeta m = a.getItemMeta();
        m.setDisplayName(name);
        m.setLore(Collections.singletonList("§8Page " + targetPage));
        a.setItemMeta(m);
        return a;
    }

    private ItemStack makeCenterInfo(int page, int totalPages, int total) {
        ItemStack i = new ItemStack(Material.COMPASS, 1);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§e§lSuspect Minage §8| §7" + total + " joueurs");
        m.setLore(Arrays.asList(
            "§7Page §f" + page + " §7/ §f" + totalPages,
            "",
            "§8Tri : ratio moddé/stone décroissant",
            "§c§l⚠ §cLes plus suspects sont en premier"
        ));
        i.setItemMeta(m);
        return i;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return new ArrayList<>();
    }
}
