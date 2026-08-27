package fr.redconflict.ring;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;

/**
 * Module ring : anneaux à effets (slots dédiés côté client moddé),
 * persistance auto-sauvegardée et drop/conservation à la mort.
 */
public class RingModule implements Module {

    /** Auto-sauvegarde des rings toutes les 5 minutes (6000 ticks). */
    private static final int AUTOSAVE_TICKS = 6000;

    private final RedConflictCore plugin;
    private RingManager manager;

    public RingModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Ring";
    }

    @Override
    public void enable() {
        this.manager = new RingManager(plugin);
        RingPacketSender sender = new RingPacketSender(plugin, manager);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, RingServerHandler.CHANNEL_C2S, plugin.getChannelGuard().wrap(new RingServerHandler(manager, sender)));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, RingPacketSender.CHANNEL_S2C);

        plugin.getServer().getPluginManager().registerEvents(new RingLoginListener(manager, sender), plugin);

        RingEffects.init(manager);
        plugin.getServer().getPluginManager().registerEvents(new RingEffectListener(), plugin);
        RingEffectListener.startTask(plugin);

        // Drop ou conservation des rings à la mort (Totem of Undying).
        plugin.getServer().getPluginManager().registerEvents(new RingDeathListener(sender), plugin);

        manager.startAutoSave(AUTOSAVE_TICKS);
    }
}
