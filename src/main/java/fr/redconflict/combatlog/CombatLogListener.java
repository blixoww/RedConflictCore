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

    /**
     * La mort met fin au combat : le tag tombe, et le widget se vide tout de suite.
     *
     * <p>Deux corrections tiennent dans ces trois lignes.
     *
     * <p>Le tag était bien levé, mais rien n'en informait le client : le widget
     * gardait son compte à rebours jusqu'au tick suivant de {@link CombatLogSender},
     * soit jusqu'à une demi-seconde après la mort — et surtout, il réapparaissait
     * au respawn le temps de ce tick. Un {@code send} immédiat pousse zéro et
     * masque le widget dans le même tick que la mort.
     *
     * <p>Le tag est aussi levé sans condition. La garde {@code timeLeft > 0}
     * laissait passer le cas où le cooldown venait d'expirer entre le dernier
     * coup et la mort : le client, lui, gardait son dernier état affiché.
     *
     * <p>Le tueur, lui, reste en combat : il peut très bien être encore aux
     * prises avec quelqu'un d'autre, et lever son tag parce qu'une de ses cibles
     * est tombée rouvrirait la déconnexion de combat.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        CooldownManager.instance().clear(player, CooldownType.COMBAT);
        sender.send(player);
    }

    public boolean hasPvP(Player player) {
        RegionManager regionManager = RedConflictCore.getInstance().getWorldGuard().getRegionManager(player.getWorld());
        ApplicableRegionSet set = regionManager.getApplicableRegions(player.getLocation());
        if (set.size() == 0)
            return true;
        return set.allows(DefaultFlag.PVP);
    }
}
