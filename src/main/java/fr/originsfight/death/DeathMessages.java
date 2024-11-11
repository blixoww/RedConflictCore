package fr.originsfight.death;


import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

public class DeathMessages implements Listener {


    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player death = event.getEntity();
        Player killer = death.getKiller();
        ItemStack killItem = killer.getItemInHand();

        if (killItem != null && killItem.hasItemMeta() && killItem.getItemMeta().hasDisplayName() &&
                (killItem.getType().name().toLowerCase().contains("sword") || killItem.getType().name().toLowerCase().contains("axe"))) {

            String displayName = killItem.getItemMeta().getDisplayName();
            TextComponent component = new TextComponent("§6✦ §e" + death.getName() + " §7a été tué par §c" + killer.getName() + " §7avec ");

            TextComponent item = new TextComponent(displayName);
            item.setColor(ChatColor.AQUA.asBungee());
            item.setBold(true);
            item.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(this.enchantToString(killItem))));

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                onlinePlayer.spigot().sendMessage(component, item);
            }

            event.setDeathMessage(null);
        } else {
            event.setDeathMessage("§6✦ §e" + death.getName() + " §7a été tué par §c" + killer.getName());
        }
    }

    private String enchantToString(ItemStack itemStack) {
        if (itemStack.getEnchantments().isEmpty()) {
            return "§7Aucun enchantements";
        } else {
            StringBuilder builder = new StringBuilder("§7Enchantements:\n");
            itemStack.getEnchantments().forEach((a, b) -> {
                String level = String.valueOf(b).replace("1", "I").replace("2", "II").replace("3", "III").replace("4", "IV").replace("5", "V");
                String name = EnchantName.valueOf(a.getName()).getName();
                builder.append("§b").append(name).append(" §l").append(level).append("\n");
            });
            return builder.toString();
        }
    }
}