package fr.originsfight.shop;

import fr.originsfight.RC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /sellall — Vend automatiquement tous les items vendables de l'inventaire au prix du shop.
 * Tient compte du Sceau du Marchand (+5% vente) si équipé.
 */
public class SellAllCommand implements CommandExecutor {

    private final ShopManager manager;

    public SellAllCommand(ShopManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(RC.ERR_PLAYER_ONLY);
            return true;
        }
        manager.handleSellAll((Player) sender);
        return true;
    }
}

