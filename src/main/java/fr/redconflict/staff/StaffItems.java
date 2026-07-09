package fr.redconflict.staff;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * Kit d'items du mode staff.
 *
 * Slot 0 : Boussole        -> TP aléatoire vers un joueur
 * Slot 1 : Glace           -> Freeze/Defreeze le joueur ciblé (clic droit sur joueur)
 * Slot 2 : Livre           -> Ouvrir les stats de minage du joueur ciblé (clic droit sur joueur)
 * Slot 4 : Livre + quill   -> Toggle staff chat
 * Slot 7 : Papier          -> Classement global Suspect Minage (/topluck)
 * Slot 8 : TNT             -> Quitter le mode staff
 */
public class StaffItems {

    public static final String COMPASS_NAME    = "§b§lTP Aleatoire §8| §7Clic droit : joueur aleatoire";
    public static final String FREEZE_NAME     = "§3§lFreeze §8| §7Clic droit sur un joueur";
    public static final String STATS_NAME      = "§e§lStats Minage §8| §7Clic droit sur un joueur";
    public static final String STAFFCHAT_NAME  = "§a§lStaff Chat §8| §7Toggle le chat staff";
    public static final String SUSPECT_MINAGE  = "§d§lSuspect Minage §8| §7Classement global xray";
    public static final String EXIT_NAME       = "§c§lQuitter le Mode Staff";

    public static void giveStaffKit(Player p) {
        p.getInventory().setItem(0, make(Material.COMPASS, COMPASS_NAME,
                "§7Clic droit dans le vide :",
                "§bTP vers un joueur aleatoire en ligne."));
        p.getInventory().setItem(1, make(Material.ICE, FREEZE_NAME,
                "§7Clic droit sur un joueur pour",
                "§3le freezer §7ou §ale defreezer."));
        p.getInventory().setItem(2, make(Material.BOOK, STATS_NAME,
                "§7Clic droit sur un joueur pour",
                "§evoir ses statistiques de minage",
                "§7(emeraude, ruby, cobalt, stone, ratio)."));
        p.getInventory().setItem(4, make(Material.SIGN, STAFFCHAT_NAME,
                "§7Clic droit pour activer/desactiver",
                "§ale mode chat staff exclusif."));
        p.getInventory().setItem(7, make(Material.PAPER, SUSPECT_MINAGE,
                "§7Clic droit pour ouvrir le",
                "§dclassement global Suspect Minage.",
                "§8(ratio moddé/stone de tous les joueurs)"));
        p.getInventory().setItem(8, make(Material.TNT, EXIT_NAME,
                "§7Clic droit pour quitter le mode staff",
                "§cet recuperer votre inventaire."));
    }

    public static ItemStack make(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isStaffItem(ItemStack item, String name) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().hasDisplayName() &&
               item.getItemMeta().getDisplayName().equals(name);
    }

    public static boolean isAnyStaffItem(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        String n = item.getItemMeta().getDisplayName();
        return n.equals(COMPASS_NAME)   || n.equals(FREEZE_NAME)
            || n.equals(STATS_NAME)     || n.equals(STAFFCHAT_NAME)
            || n.equals(SUSPECT_MINAGE) || n.equals(EXIT_NAME);
    }
}
