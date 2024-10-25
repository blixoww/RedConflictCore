package fr.originsfight.listeners;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.cooldown.CooldownType;
import fr.originsfight.utils.CooldownManager;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.TimeUnit;

public class EnderPearlListener implements Listener {

    @EventHandler
    public void onPlayerUseEnderPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Player player = event.getPlayer();
        Location loc = player.getLocation();
        ConfigurationSection zonesSection = OriginsFightCore.getInstance().getConfig().getConfigurationSection("enderpearl.zones");

        if (zonesSection != null) {
            for (String zone : zonesSection.getKeys(false)) {
                int cooldown = zonesSection.getInt(zone + ".cooldown");

                // Définir les deux points de la zone
                Location point1 = new Location(player.getWorld(),
                        zonesSection.getInt(zone + ".x1"),
                        zonesSection.getInt(zone + ".y1"),
                        zonesSection.getInt(zone + ".z1"));
                Location point2 = new Location(player.getWorld(),
                        zonesSection.getInt(zone + ".x2"),
                        zonesSection.getInt(zone + ".y2"),
                        zonesSection.getInt(zone + ".z2"));
                if (isInZone(loc, point1, point2)) {
                    if (CooldownManager.getCooldownManager().isOnCooldown(player, CooldownType.ENDERPEARL)) {
                        long remainingTime = CooldownManager.getCooldownManager().remainingTime(player, CooldownType.ENDERPEARL);
                        player.sendMessage("§cVous devez attendre " + remainingTime + " secondes avant de réutiliser une enderpearl dans cette zone.");
                        event.setCancelled(true);
                        return;
                    } else {
                        CooldownManager.getCooldownManager().set(player, cooldown, CooldownType.ENDERPEARL, TimeUnit.SECONDS);
                        player.sendMessage("§aVous avez utilisé une enderpearl. Cooldown appliqué pour " + cooldown + " secondes.");
                    }
                }
            }
        }
    }

    // Vérifie si une location est dans la zone entre deux points
    private boolean isInZone(Location loc, Location point1, Location point2) {
        double xMin = Math.min(point1.getX(), point2.getX());
        double yMin = Math.min(point1.getY(), point2.getY());
        double zMin = Math.min(point1.getZ(), point2.getZ());
        double xMax = Math.max(point1.getX(), point2.getX());
        double yMax = Math.max(point1.getY(), point2.getY());
        double zMax = Math.max(point1.getZ(), point2.getZ());

        return loc.getX() >= xMin && loc.getX() <= xMax &&
                loc.getY() >= yMin && loc.getY() <= yMax &&
                loc.getZ() >= zMin && loc.getZ() <= zMax;
    }
}
