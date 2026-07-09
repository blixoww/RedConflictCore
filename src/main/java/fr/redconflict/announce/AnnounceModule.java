package fr.redconflict.announce;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;

/**
 * Module annonce : diffusion d'annonces stylées sur tous les serveurs du
 * cluster (BungeeCord Forward) — réception + commande staff /annonce.
 */
public class AnnounceModule implements Module {

    private final RedConflictCore plugin;

    public AnnounceModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Announce";
    }

    @Override
    public void enable() {
        AnnounceService service = new AnnounceService(plugin);
        service.register();
        new CommandRegistrar(plugin).register("annonce", new AnnounceCommand(plugin, service));
    }
}
