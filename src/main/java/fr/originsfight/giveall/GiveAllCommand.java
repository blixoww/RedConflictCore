package fr.originsfight.giveall;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class GiveAllCommand implements CommandExecutor {

    public static final String INV_TITLE = "§8[§c§lGiveAll§8] §7Items à distribuer";
    public static final int SLOT_SEND   = 49;
    public static final int SLOT_CANCEL = 45;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(RC.ERR_PLAYER_ONLY); return true; }
        Player player = (Player) sender;
        if (!player.isOp() && !player.hasPermission("staff.giveall")) {
            player.sendMessage(RC.ERR_NO_PERM); return true;
        }
        player.openInventory(buildGiveAllInventory());
        player.sendMessage(RC.GIVEALL_HINT);
        return true;
    }

    public static Inventory buildGiveAllInventory() {
        Inventory inv = Bukkit.createInventory(null, 54, INV_TITLE);
        ItemStack glass = makeGlass();
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);
        inv.setItem(SLOT_SEND,   makeSendButton());
        inv.setItem(SLOT_CANCEL, makeCancelButton());
        return inv;
    }

    public static ItemStack makeSendButton() {
        ItemStack item = new ItemStack(Material.EMERALD_BLOCK, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a§l✔ Distribuer à tous");
        meta.setLore(Arrays.asList("§7Distribue les items à tous les joueurs en ligne."));
        item.setItemMeta(meta); return item;
    }

    public static ItemStack makeCancelButton() {
        ItemStack item = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§c§l✖ Annuler");
        meta.setLore(Arrays.asList("§7Ferme sans distribuer."));
        item.setItemMeta(meta); return item;
    }

    private static ItemStack makeGlass() {
        ItemStack item = new ItemStack(Material.STAINED_GLASS_PANE, 1, (byte) 7);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta); return item;
    }
}


