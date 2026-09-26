package fr.redconflict.vote;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Le menu de {@code /vote} : un coffre en lecture seule, construit à chaque
 * ouverture depuis {@code vote/recompenses.yml} — la table même du tirage.
 * Modifier un lot, un poids ou le palier dans le fichier (puis
 * {@code /rcvote reload}) change le menu, sans liste à tenir à jour à côté.
 *
 * <pre>
 *  rangée 1 : TES VOTES · VOTER · PALIER DE FIDÉLITÉ  — les trois choses qu'on vient chercher
 *  rangée 2 : [vert]   lots courants     (≥ 8 %)
 *  rangée 3 : [jaune]  lots peu courants (2 à 8 %)
 *  rangée 4 : [rouge]  lots rares        (< 2 %)
 *  rangée 5 : fermer
 * </pre>
 *
 * <p>Une rangée par rareté, étiquetée par une vitre de couleur : on lit d'un
 * coup d'œil ce qui tombe souvent et ce qui est rare, sans lire un seul
 * pourcentage. Si un niveau compte plus de 7 lots, le menu retombe sur une
 * grille simple triée du plus courant au plus rare.
 */
public final class VoteMenu implements Listener {

    private static final int SIZE = 45;
    private static final int SLOT_COUNTER = 2;
    private static final int SLOT_VOTE = 4;
    private static final int SLOT_PALIER = 6;
    private static final int SLOT_CLOSE = 40;
    /** Première case de chaque rangée de lots (rangées 2 à 4). */
    private static final int[] ROW_START = {9, 18, 27};
    /** Cases des lots en grille simple (repli). */
    private static final int[] LOT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    /** Niveaux de rareté : libellé, couleur de vitre (data), seuil minimal en %. */
    private static final String[] TIER_NAME = {"Lots courants", "Lots peu courants", "Lots rares"};
    private static final ChatColor[] TIER_COLOR = {ChatColor.GREEN, ChatColor.YELLOW, ChatColor.RED};
    private static final short[] TIER_GLASS = {5, 4, 14};
    private static final double[] TIER_MIN = {8.0, 2.0, 0.0};

    private final org.bukkit.plugin.Plugin plugin;
    private final VoteRewards rewards;

    public VoteMenu(org.bukkit.plugin.Plugin plugin, VoteRewards rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    /** Marque nos inventaires : le titre seul ne suffit pas à les reconnaître. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    /**
     * @param cible pseudo consulté, ou {@code null} pour soi-même
     * @param votes compteur cumulé, ou -1 s'il est inconnu (base indisponible)
     */
    public void open(Player viewer, String cible, int votes) {
        Holder holder = new Holder();
        String title = cible == null ? "Vote · Récompenses" : "Votes · " + cible;
        if (title.length() > 32) title = title.substring(0, 32);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inventory = inv;

        ItemStack dark = item(Material.STAINED_GLASS_PANE, (short) 15, 1, " ", null);
        for (int i = 0; i < 9; i++) inv.setItem(i, dark);
        for (int i = 36; i < SIZE; i++) inv.setItem(i, dark);

        // ── En-tête : les trois choses qu'on vient chercher ──
        inv.setItem(SLOT_COUNTER, counter(cible, votes));
        inv.setItem(SLOT_VOTE, voteButton());
        inv.setItem(SLOT_PALIER, palierItem());
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, (short) 0, 1, ChatColor.RED + "Fermer", null));

        // ── Les lots, rangés par rareté ──
        int total = rewards.poidsTotal();
        List<VoteLot> lots = rewards.lotsAuTirage();
        List<List<VoteLot>> tiers = new ArrayList<List<VoteLot>>();
        for (int t = 0; t < TIER_NAME.length; t++) tiers.add(new ArrayList<VoteLot>());
        for (VoteLot lot : lots) tiers.get(tierOf(pct(lot, total))).add(lot);

        boolean fits = true;
        for (List<VoteLot> t : tiers) if (t.size() > 7) fits = false;

        if (fits) {
            for (int t = 0; t < TIER_NAME.length; t++) {
                int start = ROW_START[t];
                List<String> legend = new ArrayList<String>();
                legend.add(ChatColor.GRAY + legendText(t));
                ItemStack label = item(Material.STAINED_GLASS_PANE, TIER_GLASS[t], 1,
                        TIER_COLOR[t] + "" + ChatColor.BOLD + TIER_NAME[t], legend);
                inv.setItem(start, label);
                inv.setItem(start + 8, label);
                List<VoteLot> row = tiers.get(t);
                for (int i = 0; i < row.size(); i++) {
                    inv.setItem(start + 1 + i, lotItem(row.get(i), total, t));
                }
            }
        } else {
            for (int i = 0; i < lots.size() && i < LOT_SLOTS.length; i++) {
                VoteLot lot = lots.get(i);
                inv.setItem(LOT_SLOTS[i], lotItem(lot, total, tierOf(pct(lot, total))));
            }
        }

        viewer.openInventory(inv);
    }

    private static double pct(VoteLot lot, int total) {
        return total > 0 ? lot.poids * 100.0 / total : 0.0;
    }

    private static int tierOf(double pct) {
        for (int t = 0; t < TIER_MIN.length; t++) if (pct >= TIER_MIN[t]) return t;
        return TIER_MIN.length - 1;
    }

    private static String legendText(int tier) {
        switch (tier) {
            case 0:  return "Tombent souvent (8 % et plus chacun)";
            case 1:  return "De temps en temps (2 à 8 %)";
            default: return "Rares (moins de 2 %)";
        }
    }

    /** Un lot : son nom, sa chance, et ce qu'elle veut dire concrètement. */
    private ItemStack lotItem(VoteLot lot, int total, int tier) {
        double pct = pct(lot, total);
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Chance : " + TIER_COLOR[tier] + pourcent(pct));
        if (pct > 0) {
            long oneIn = Math.max(1L, Math.round(100.0 / pct));
            lore.add(ChatColor.DARK_GRAY + "≈ 1 vote sur " + oneIn);
        }
        return icon(lot, couleur(lot.nom), lore);
    }

    private ItemStack voteButton() {
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Clique ici : le lien de vote");
        lore.add(ChatColor.GRAY + "s'affiche dans le chat.");
        lore.add("");
        int tirages = rewards.tirages();
        lore.add(ChatColor.WHITE + "Chaque vote = " + ChatColor.GREEN + tirages
                + (tirages > 1 ? " lots" : " lot") + ChatColor.WHITE + " au hasard");
        lore.add(ChatColor.GRAY + "parmi ceux ci-dessous.");
        int pb = rewards.pbParVote();
        if (pb > 0) lore.add(ChatColor.AQUA + "+" + pb + " PB " + ChatColor.GRAY + "en plus, à chaque vote.");
        ItemStack stack = item(Material.EMERALD, (short) 0, 1,
                ChatColor.GREEN + "" + ChatColor.BOLD + "» VOTER «", lore);
        // Brillance d'enchantement (masquée) : le bouton principal se voit d'abord.
        stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 1);
        return stack;
    }

    private ItemStack palierItem() {
        int palier = rewards.palier();
        VoteLot lotPalier = rewards.lotPalier();
        if (palier <= 0 || lotPalier == null) {
            return item(Material.STAINED_GLASS_PANE, (short) 15, 1, " ", null);
        }
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Tous les " + ChatColor.WHITE + palier + " votes"
                + ChatColor.GRAY + ", tu reçois");
        lore.add(ChatColor.GRAY + "EN PLUS du lot tiré au hasard :");
        lore.add(ChatColor.DARK_GRAY + "» " + couleur(lotPalier.nom));
        return icon(lotPalier, ChatColor.GOLD + "" + ChatColor.BOLD + "Récompense de fidélité", lore);
    }

    // ── Clics ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        event.setCancelled(true);   // lecture seule, y compris maj-clic et touches numériques
        if (!(event.getWhoClicked() instanceof Player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;   // clic dans l'inventaire du joueur

        final Player player = (Player) event.getWhoClicked();
        if (slot == SLOT_CLOSE) {
            closeLater(player);
        } else if (slot == SLOT_VOTE) {
            closeLater(player);
            sendLink(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private void closeLater(final Player player) {
        // Fermer pendant l'événement de clic laisse l'objet « fantôme » au curseur.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { player.closeInventory(); }
        });
    }

    private void sendLink(Player player) {
        String lien = rewards.lienVote();
        if (lien == null || lien.trim().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Aucun lien de vote n'est configuré.");
            return;
        }
        TextComponent msg = new TextComponent(ChatColor.GREEN + "✔ Vote ici : ");
        TextComponent link = new TextComponent(lien);
        link.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, lien));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Clique pour ouvrir la page de vote").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        msg.addExtra(link);
        player.spigot().sendMessage(msg);
    }

    // ── Construction des objets ──────────────────────────────────────────────

    private ItemStack counter(String cible, int votes) {
        List<String> lore = new ArrayList<String>();
        String titre;
        if (votes < 0) {
            titre = ChatColor.GOLD + "" + ChatColor.BOLD + (cible == null ? "Tes votes" : "Votes de " + cible);
            lore.add(ChatColor.GRAY + "Compteur indisponible pour le moment.");
        } else {
            titre = ChatColor.GOLD + "" + ChatColor.BOLD + (cible == null ? "Tes votes : " : "Votes de " + cible + " : ")
                    + ChatColor.WHITE + votes;
            int palier = rewards.palier();
            if (palier > 0 && rewards.lotPalier() != null) {
                int fait = votes % palier;
                int reste = palier - fait;
                lore.add(barre(fait, palier) + ChatColor.GRAY + " " + fait + "/" + palier);
                lore.add(ChatColor.GRAY + "Plus que " + ChatColor.WHITE + reste
                        + ChatColor.GRAY + (reste > 1 ? " votes" : " vote") + " avant la");
                lore.add(ChatColor.GRAY + "récompense de fidélité.");
            }
        }
        return item(Material.EXP_BOTTLE, (short) 0, 1, titre, lore);
    }

    /**
     * Icône d'un lot : {@code icone:} du YAML si renseignée, sinon déduite de la
     * première commande — l'objet donné par un {@code give}, un lingot d'or pour
     * de l'argent, une clé pour une caisse, un coffre pour un kit.
     */
    private static ItemStack icon(VoteLot lot, String name, List<String> lore) {
        Material mat = null;
        short data = 0;
        int amount = 1;

        if (!lot.icone.isEmpty()) {
            String[] p = lot.icone.split(":");
            mat = Material.matchMaterial(p[0]);
            if (p.length > 1) data = parseShort(p[1]);
        }
        for (int i = 0; mat == null && i < lot.commandes.size(); i++) {
            String[] w = lot.commandes.get(i).trim().split("\\s+");
            String cmd = w.length > 0 ? w[0].toLowerCase(Locale.ROOT) : "";
            if (cmd.equals("give") && w.length >= 3) {
                String[] m = w[2].split(":");
                mat = Material.matchMaterial(m[0]);
                if (m.length > 1) data = parseShort(m[1]);
                if (w.length >= 4) amount = Math.max(1, Math.min(64, parseShort(w[3])));
            } else if ((cmd.equals("eco") || cmd.equals("money")) && w.length >= 2) {
                mat = Material.GOLD_INGOT;
            } else if (cmd.equals("cc") || cmd.equals("crate") || cmd.equals("crates")) {
                mat = crateKey(w);
            } else if (cmd.equals("kit")) {
                mat = Material.CHEST;
            }
        }
        if (mat == null && lot.pb > 0) mat = Material.NETHER_STAR;
        if (mat == null || mat == Material.AIR) mat = Material.PAPER;
        return item(mat, data, amount, name, lore);
    }

    /** « cc give physical acier 1 %player% » → la clé du fork si elle existe. */
    private static Material crateKey(String[] w) {
        String type = w.length >= 4 ? w[3].toLowerCase(Locale.ROOT) : "";
        String key;
        if (type.startsWith("acier") || type.startsWith("steel")) key = "STEEL_KEY";
        else if (type.startsWith("emeraude") || type.startsWith("émeraude") || type.startsWith("emerald")) key = "EMERALD_KEY";
        else if (type.startsWith("rubis") || type.startsWith("ruby")) key = "RUBY_KEY";
        else if (type.startsWith("cobalt")) key = "COBALT_KEY";
        else key = "";
        Material m = key.isEmpty() ? null : Material.getMaterial(key);
        return m != null ? m : Material.TRIPWIRE_HOOK;
    }

    private static ItemStack item(Material mat, short data, int amount, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat, amount, data);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String barre(int fait, int palier) {
        int plein = (int) Math.round(fait * 20.0 / palier);
        StringBuilder b = new StringBuilder().append(ChatColor.GREEN);
        for (int i = 0; i < plein; i++) b.append('|');
        b.append(ChatColor.DARK_GRAY);
        for (int i = plein; i < 20; i++) b.append('|');
        return b.toString();
    }

    private static short parseShort(String s) {
        try {
            return Short.parseShort(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String couleur(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s).trim();
    }

    /** « 14 % », « 9,5 % », « 0,8 % » : pas de décimale inutile. */
    private static String pourcent(double pct) {
        double r = Math.round(pct * 10.0) / 10.0;
        if (r == Math.rint(r)) return String.format(Locale.FRANCE, "%d %%", (long) r);
        return String.format(Locale.FRANCE, "%.1f %%", r);
    }
}
