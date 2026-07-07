package fr.originsfight.faction;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;

/**
 * Module faction : données envoyées au client moddé — positions minimap
 * (toujours actives, filtrées anti-triche) et, si RedFaction est présent
 * (serveur Faction uniquement), tag/relation de faction et zone de claim.
 */
public class FactionModule implements Module {

    private final OriginsFightCore plugin;

    public FactionModule(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Faction";
    }

    @Override
    public void enable() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:FACTION_S2C");

        // Minimap : le sender filtre avant envoi (jamais d'ennemi ni de neutre) et
        // dégrade proprement si Factions est absent (les positions d'amis restent envoyées).
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "CUSTOM:MMAP_S2C");
        new MinimapPositionSender(plugin).start();

        // Les `new` restent dans la branche conditionnelle pour que la JVM ne charge
        // jamais les classes dépendant de RedFaction quand celui-ci est absent (Minage).
        if (plugin.getServer().getPluginManager().getPlugin("RedFaction") == null) {
            plugin.getLogger().info("[Faction] Plugin RedFaction absent — features faction (HUD tag, zone de claim) désactivées.");
            return;
        }
        // Tag + relation de faction envoyés périodiquement aux clients proches.
        new FactionDataSender(plugin).start();
        // Zone (claim) : envoie au client la faction propriétaire du chunk courant,
        // y compris sans franchissement de chunk (mise à jour périodique).
        FactionZoneSender zoneSender = new FactionZoneSender(plugin);
        plugin.getServer().getPluginManager().registerEvents(zoneSender, plugin);
        zoneSender.startPeriodicUpdate();
    }
}
