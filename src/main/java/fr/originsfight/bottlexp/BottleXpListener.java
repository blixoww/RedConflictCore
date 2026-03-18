package fr.originsfight.bottlexp;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class BottleXpListener implements Listener {

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        // Seulement clic droit (air ou bloc)
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getItemInHand();

        if (!BottleXpItem.isBottleXp(itemInHand)) return;

        int levels = BottleXpItem.getLevels(itemInHand);
        if (levels <= 0) return;

        // Annuler l'event pour éviter tout comportement vanilla de l'EXP_BOTTLE
        event.setCancelled(true);

        // Retirer une bouteille de la main
        if (itemInHand.getAmount() > 1) {
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        } else {
            player.setItemInHand(null);
        }

        // Donner les niveaux — on ajoute aux niveaux actuels (accumulation possible)
        int currentLevel = player.getLevel();
        float currentExp = player.getExp();

        // Calculer le total d'XP brut pour ajouter proprement les niveaux
        // (on additionne les niveaux directement car on stocke des niveaux, pas des points XP)
        player.setLevel(currentLevel + levels);
        // On conserve l'EXP partielle du joueur
        player.setExp(currentExp);

        player.sendMessage(ChatColor.GREEN + "✔ Vous avez récupéré " + ChatColor.GOLD + levels
                + " niveau" + (levels > 1 ? "x" : "") + ChatColor.GREEN
                + " ! Vous avez maintenant " + ChatColor.GOLD + player.getLevel() + " niveau"
                + (player.getLevel() > 1 ? "x" : "") + ChatColor.GREEN + ".");
    }
}

