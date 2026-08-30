package fr.redconflict.essentials.listener;

import fr.redconflict.essentials.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Fait apparaître les nouveaux joueurs au spawn défini par {@code /setspawn}.
 *
 * <p><b>Pourquoi ça ne marchait pas tout seul.</b> {@link SpawnService#set}
 * appelle bien {@code World.setSpawnLocation}, mais ça ne suffit pas pour un
 * premier passage, et ce pour deux raisons :
 *
 * <ul>
 *   <li>un joueur inconnu apparaît au spawn du <b>monde principal</b>
 *       ({@code Bukkit.getWorlds().get(0)}), pas à celui du monde où la commande
 *       a été tapée. Un {@code /setspawn} passé ailleurs n'était donc jamais vu
 *       par les nouveaux ;</li>
 *   <li>{@code setSpawnLocation} ne prend que des coordonnées de bloc
 *       <b>entières</b> : l'orientation du regard est perdue, et le joueur
 *       atterrit au coin du bloc plutôt qu'à l'endroit exact où se tenait
 *       l'administrateur.</li>
 * </ul>
 *
 * <p>Cet événement de Spigot est le seul endroit qui règle les deux : il se
 * déclenche pendant la connexion, <b>avant</b> que le joueur soit posé dans le
 * monde, et accepte une position complète — monde, décimales et orientation.
 *
 * <p>Ne concerne que le <b>premier</b> passage. Un joueur déjà venu revient là
 * où il s'est déconnecté : c'est ce qu'il attend, et le déplacer d'autorité à
 * chaque connexion serait un tout autre comportement — que personne n'a demandé.
 */
public class SpawnListener implements Listener {

    private final SpawnService spawns;

    public SpawnListener(SpawnService spawns) {
        this.spawns = spawns;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        if (event.getPlayer().hasPlayedBefore()) return;

        Location spawn = spawns.find();
        // Aucun /setspawn passé (ou son monde n'est plus chargé) : on laisse le
        // serveur faire comme avant. Poser un joueur n'importe où par défaut
        // serait pire que de ne rien faire.
        if (spawn == null) return;

        event.setSpawnLocation(spawn);
    }
}
