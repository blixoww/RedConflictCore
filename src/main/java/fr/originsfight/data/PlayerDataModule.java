package fr.originsfight.data;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.core.Module;
import fr.originsfight.core.command.CommandRegistrar;
import fr.originsfight.db.Database;
import fr.originsfight.ks.KsCommand;
import fr.originsfight.ks.KsListener;
import fr.originsfight.profil.ProfilCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Module données joueurs : base des statistiques (kills, deaths, temps de jeu,
 * PB...), commande /ks et fiche de réputation /profil (GUI client moddé).
 * Le temps de jeu des joueurs connectés est comptabilisé à la désactivation.
 */
public class PlayerDataModule implements Module {

    private final OriginsFightCore plugin;
    private final Database database;
    private PlayerDatabase playerDatabase;

    public PlayerDataModule(OriginsFightCore plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String getName() {
        return "PlayerData";
    }

    @Override
    public void enable() throws Exception {
        this.playerDatabase = new PlayerDatabase(database);
        if (!playerDatabase.init()) {
            this.playerDatabase = null;
            throw new IllegalStateException("Échec de l'initialisation de la base de données joueurs");
        }

        CommandRegistrar commands = new CommandRegistrar(plugin);
        commands.register("ks", new KsCommand(plugin, playerDatabase));
        plugin.getServer().getPluginManager().registerEvents(new KsListener(playerDatabase, plugin), plugin);

        // /profil : fiche publique d'un joueur, ouvre le GUI côté client moddé.
        commands.register("profil", new ProfilCommand(plugin));
    }

    @Override
    public void disable() {
        if (playerDatabase == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long joinTime = KsListener.getJoinTime(player.getUniqueId());
            if (joinTime != null) {
                long seconds = (System.currentTimeMillis() - joinTime) / 1000;
                playerDatabase.addPlaytime(player.getUniqueId(), seconds);
            }
        }
        playerDatabase.close();
    }

    /** Requis par les modules XpBoost, PB et les handlers de packets. */
    public PlayerDatabase getPlayerDatabase() {
        return playerDatabase;
    }
}
