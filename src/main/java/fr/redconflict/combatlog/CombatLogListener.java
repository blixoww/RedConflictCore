package fr.redconflict.combatlog;

import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import fr.redconflict.RedConflictCore;
import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import fr.redconflict.cooldown.CooldownType;
import fr.redconflict.cooldown.CooldownManager;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

public class CombatLogListener implements Listener {

    // WorldGuard est optionnel : calculé une fois au démarrage. Sur un serveur sans WorldGuard
    // (ex. Minage), hasPvP() n'est jamais appelé → la JVM ne charge pas les classes WorldGuard
    // (pas de NoClassDefFoundError), et le PvP est autorisé partout par défaut.
    private final boolean wgPresent = RedConflictCore.getInstance().getWorldGuard() != null;

    private final CombatLogSender sender;

    public CombatLogListener(CombatLogSender sender) {
        this.sender = sender;
    }

    @EventHandler
    public void onDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        if (wgPresent && (!hasPvP(victim) || !hasPvP(attacker))) {
            event.setCancelled(true);
            return;
        }
        tag(victim);
        tag(attacker);
    }

    /**
     * Résout le joueur responsable des dégâts : coup direct (l'entité est le joueur) ou projectile
     * tiré par un joueur (flèche, etc. — le tireur est un joueur). Retourne {@code null} si les
     * dégâts ne proviennent pas d'un joueur.
     */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    /** Passe un joueur en Combat Tag (30s) et pousse immédiatement l'état au widget client. */
    private void tag(Player p) {
        if (p.isOp()) return;
        if (CooldownManager.instance().timeLeft(p, CooldownType.COMBAT) == 0) {
            p.sendMessage(RC.CT_ENTER);
        }
        CooldownManager.instance().set(p, CooldownType.COMBAT, 30, TimeUnit.SECONDS);
        sender.send(p);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (CooldownManager.instance().timeLeft(player, CooldownType.COMBAT) > 0) {
            player.setHealth(0);
            Bukkit.broadcastMessage(Text.fmt(RC.CT_LOGOUT_DEATH, player.getName()));
        }
        sender.forget(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (CooldownManager.instance().timeLeft(player, CooldownType.COMBAT) > 0) {
            CooldownManager.instance().clear(player);
        }
    }

    public boolean hasPvP(Player player) {
        RegionManager regionManager = RedConflictCore.getInstance().getWorldGuard().getRegionManager(player.getWorld());
        ApplicableRegionSet set = regionManager.getApplicableRegions(player.getLocation());
        if (set.size() == 0)
            return true;
        return set.allows(DefaultFlag.PVP);
    }
}
