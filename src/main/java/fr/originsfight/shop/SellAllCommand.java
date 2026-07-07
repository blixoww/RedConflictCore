package fr.originsfight.shop;

import fr.originsfight.core.command.CoreCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /sellall — Vend automatiquement tous les items vendables de l'inventaire au prix du shop.
 * Tient compte du Sceau du Marchand (+5% vente) si équipé.
 */
public class SellAllCommand extends CoreCommand {

    private final ShopManager manager;

    public SellAllCommand(JavaPlugin plugin, ShopManager manager) {
        super(plugin, "sellall", true);
        this.manager = manager;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        manager.handleSellAll((Player) sender);
    }
}

