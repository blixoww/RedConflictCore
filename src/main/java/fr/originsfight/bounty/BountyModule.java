package fr.originsfight.bounty;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;

/**
 * Module bounty : primes PvP (/prime), killstreaks et annonces, avec suivi
 * des départs de faction (anti-abus de prime entre alliés).
 */
public class BountyModule implements Module {

    private final OriginsFightCore plugin;

    public BountyModule(OriginsFightCore plugin) {
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
        plugin.getServer().getPluginManager().registerEvents(manager.getFactionTracker(), plugin);
    }

}
