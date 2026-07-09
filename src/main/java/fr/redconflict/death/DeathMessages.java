package fr.redconflict.death;

import fr.redconflict.annonyme.AnonymeManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Message de mort PvP custom : « X a été tué par Y avec <arme> », avec les
 * enchantements de l'arme au survol. Les pseudos sont masqués quand la victime
 * ou le tueur est en /annonyme.
 */
public class DeathMessages implements Listener {

    private static final String ANON_NAME = "Identité masquée";

    private final AnonymeManager anonymeManager;

    public DeathMessages(AnonymeManager anonymeManager) {
        this.anonymeManager = anonymeManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }

        String victimName = anonymeManager.isAnonymous(victim) ? ANON_NAME : victim.getName();
        String killerName = anonymeManager.isAnonymous(killer) ? ANON_NAME : killer.getName();

        event.setDeathMessage(null);
        TextComponent message = new TextComponent("§6✦ §e" + victimName + " §7a été tué par §c" + killerName);

        ItemStack weapon = killer.getItemInHand();
        if (isNamedWeapon(weapon)) {
            String displayName = weapon.getItemMeta().getDisplayName();
            String enchants = enchantsToString(weapon);
            TextComponent weaponComponent = new TextComponent(" §7avec §b" + displayName);
            weaponComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{
                    new TextComponent("§b" + displayName + (enchants.isEmpty() ? "" : "\n§7" + enchants))}));
            message.addExtra(weaponComponent);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(message);
        }
    }

    /** Seules les armes renommées (épée/hache) sont détaillées dans le message. */
    private static boolean isNamedWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
            return false;
        }
        String type = item.getType().name().toLowerCase();
        return type.contains("sword") || type.contains("axe");
    }

    private static String enchantsToString(ItemStack item) {
        StringBuilder builder = new StringBuilder();
        item.getEnchantments().forEach((enchant, level) ->
                builder.append("§b").append(EnchantName.of(enchant.getName()))
                        .append(" §l").append(roman(level)).append(" "));
        return builder.toString().trim();
    }

    private static String roman(int level) {
        String[] romans = {"I", "II", "III", "IV", "V"};
        return level >= 1 && level <= romans.length ? romans[level - 1] : String.valueOf(level);
    }
}
