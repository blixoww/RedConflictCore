package fr.redconflict.bounty;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import fr.redconflict.faction.FactionHook;

/**
 * Module bounty : primes PvP (/prime), killstreaks et annonces, avec suivi
 * des départs de faction (anti-abus de prime entre alliés).
 */
public class BountyModule implements Module {

    private final RedConflictCore plugin;

    public BountyModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Bounty";
    }

    @Override
    public void enable() throws Exception {
        KillstreakManager killstreaks = new KillstreakManager();
        BountyManager manager = new BountyManager(killstreaks);
        if (!manager.enable(plugin)) {
            throw new IllegalStateException("Échec de l'initialisation du système de primes");
        }
        new CommandRegistrar(plugin).register("prime", new BountyCommand(plugin, manager, killstreaks));
        plugin.getServer().getPluginManager().registerEvents(new BountyListener(manager, killstreaks), plugin);
        // L'anti-abus entre ex-coéquipiers n'a de sens qu'avec les factions :
        // sans RedFaction (Minage), inutile d'écouter toutes les commandes.
        if (FactionHook.isEnabled()) {
            plugin.getServer().getPluginManager().registerEvents(manager.getFactionTracker(), plugin);
        }
    }

}
