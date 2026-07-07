package fr.originsfight.giveall;

import fr.originsfight.core.command.CoreCommand;
import fr.originsfight.core.text.RC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;

/**
 * /giveall (staff) — ouvre l'inventaire de préparation : les slots 0-44
 * reçoivent les items à distribuer, la ligne du bas porte les boutons
 * Envoyer/Annuler (voir {@link GiveAllListener}).
 */
public class GiveAllCommand extends CoreCommand {

    public static final String INV_TITLE = "§8[§c§lGiveAll§8] §7Items à distribuer";
    public static final int SLOT_SEND   = 49;
    public static final int SLOT_CANCEL = 45;

    public GiveAllCommand(JavaPlugin plugin) {
        super(plugin, "giveall", true);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("staff.giveall")) {
            player.sendMessage(RC.ERR_NO_PERM);
            return;
        }
        player.openInventory(buildInventory());
        player.sendMessage(RC.GIVEALL_HINT);
    }

    private static Inventory buildInventory() {
        Inventory inv = Bukkit.createInventory(null, 54, INV_TITLE);
        ItemStack glass = button(new ItemStack(Material.STAINED_GLASS_PANE, 1, (byte) 7), " ", null);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, glass);
        }
        inv.setItem(SLOT_SEND, button(new ItemStack(Material.EMERALD_BLOCK),
                "§a§l✔ Distribuer à tous", "§7Distribue les items à tous les joueurs en ligne."));
        inv.setItem(SLOT_CANCEL, button(new ItemStack(Material.BARRIER),
                "§c§l✖ Annuler", "§7Ferme sans distribuer."));
        return inv;
    }

    private static ItemStack button(ItemStack item, String name, String lore) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(Collections.singletonList(lore));
        }
        item.setItemMeta(meta);
        return item;
    }
}
