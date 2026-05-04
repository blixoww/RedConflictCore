package fr.originsfight.death;

import fr.originsfight.annonyme.AnonymeManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

public class DeathMessages implements Listener {

    private static final String ANON_NAME = "Identité masquée";

    private final AnonymeManager anonymeManager;

    public DeathMessages(AnonymeManager anonymeManager) {
        this.anonymeManager = anonymeManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player death = event.getEntity();
        Player killer = death.getKiller();

        if (killer == null) return;

        String deathName  = anonymeManager.isAnonymous(death)  ? ANON_NAME : death.getName();
        String killerName = anonymeManager.isAnonymous(killer) ? ANON_NAME : killer.getName();

        ItemStack killItem = killer.getItemInHand();
        event.setDeathMessage(null);

        // Construire le message avec TextComponent
        TextComponent message = new TextComponent("§6✦ §e" + deathName + " §7a été tué par §c" + killerName);

        if (killItem != null && killItem.hasItemMeta()
                && killItem.getItemMeta().hasDisplayName()
                && (killItem.getType().name().toLowerCase().contains("sword")
                || killItem.getType().name().toLowerCase().contains("axe"))) {

            String displayName = killItem.getItemMeta().getDisplayName();
            String enchants = enchantToString(killItem);

            // Créer un composant pour le nom de l'arme avec hover
            TextComponent weaponComponent = new TextComponent(" §7avec §b" + displayName);
            String hoverText = "§b" + displayName + (enchants.isEmpty() ? "" : "\n§7" + enchants);
            weaponComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{new TextComponent(hoverText)}));

            message.addExtra(weaponComponent);
        }

        // Envoyer le message à tous les joueurs
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(message);
        }
    }

    private String enchantToString(ItemStack itemStack) {
        if (itemStack.getEnchantments().isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        itemStack.getEnchantments().forEach((ench, level) -> {
            String lvl = String.valueOf(level)
                    .replace("1", "I").replace("2", "II").replace("3", "III")
                    .replace("4", "IV").replace("5", "V");
            try {
                String name = EnchantName.valueOf(ench.getName()).getName();
                builder.append("§b").append(name).append(" §l").append(lvl).append(" ");
            } catch (IllegalArgumentException ignored) {
                builder.append("§b").append(ench.getName()).append(" §l").append(lvl).append(" ");
            }
        });
        return builder.toString().trim();
    }
}
