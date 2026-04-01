package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.RC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Listener pour le système de primes.
 * Lorsqu'un joueur avec une prime est tué, le tueur reçoit la somme.
 */
public class BountyListener implements Listener {

    private final BountyManager manager;

    public BountyListener(BountyManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        // Vérifier si la victime avait une prime
        BountyInfo bounty = manager.getBounty(victim.getUniqueId());
        if (bounty == null) return;

        // Verser la prime au tueur
        Economy eco = OriginsFightCore.getInstance().getEconomy();
        if (eco != null) {
            eco.depositPlayer(killer, bounty.getAmount());
        }

        // Retirer la prime
        manager.removeBounty(victim.getUniqueId());

        // Message au tueur
        killer.sendMessage(RC.fmt(RC.BOUNTY_CLAIMED, victim.getName(), bounty.getAmount()));

        // Annonce globale
        for (String line : RC.fmt(RC.BOUNTY_CLAIMED_BROADCAST,
                killer.getName(), victim.getName(), bounty.getAmount()).split("\n")) {
            Bukkit.broadcastMessage(line);
        }
    }
}
