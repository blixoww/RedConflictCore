package fr.redconflict.db;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remise de relais : sauvegarde et libération AVANT d'envoyer un joueur sur un
 * autre serveur.
 *
 * <p><b>Pourquoi ça ne pouvait pas marcher autrement.</b> On avait supposé que
 * Velocity ferme la connexion à l'ancien serveur dès que la nouvelle est
 * établie. C'est l'inverse : il termine d'abord la séquence de connexion sur le
 * serveur d'arrivée, et ne coupe l'ancienne qu'une fois que celui-ci a envoyé
 * son {@code JoinGame}. Attendre la libération du verrou pendant le pré-login du
 * serveur d'arrivée créait donc un blocage circulaire — l'arrivée attendait un
 * départ qui attendait l'arrivée — résolu seulement par l'expiration du délai.
 *
 * <p>Le seul moment où le serveur de départ peut agir utilement, c'est
 * <i>avant</i> de demander le transfert : là, il tient encore le joueur, ses
 * données sont à jour, et rien ne l'attend. D'où cette classe, appelée juste
 * avant l'envoi du message {@code Connect} au proxy.
 *
 * <p>La marque posée ici évite la double sauvegarde : le {@code PlayerQuitEvent}
 * arrivera plus tard, alors que le joueur est déjà en train de jouer ailleurs,
 * et réécrirait dans H2 un instantané périmé par-dessus le sien.
 */
public class HandoffService {

    private final PlayerLockService lockService;
    private final PlayerDataSyncService syncService; // nullable
    private final String serverId;

    /** Joueurs dont les données sont déjà sauvegardées et le verrou relâché. */
    private final Set<UUID> handedOff =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public HandoffService(PlayerLockService lockService, PlayerDataSyncService syncService,
                          String serverId) {
        this.lockService = lockService;
        this.syncService = syncService;
        this.serverId = serverId;
    }

    /**
     * Sauvegarde l'état du joueur et relâche son verrou, puis le marque comme
     * transféré. À appeler sur le thread principal, juste avant le transfert.
     *
     * @return {@code true} si la remise a réussi ; {@code false} si la
     *         sauvegarde a échoué — dans ce cas l'appelant devrait renoncer au
     *         transfert plutôt que d'envoyer le joueur sans ses données.
     */
    public boolean handOff(Player player) {
        UUID uuid = player.getUniqueId();
        if (syncService != null) {
            try {
                syncService.saveNow(player);
            } catch (Exception e) {
                return false;
            }
        }
        lockService.release(uuid, serverId);
        handedOff.add(uuid);
        return true;
    }

    /**
     * Le joueur a-t-il déjà été remis ? Consommé par le {@code PlayerQuitEvent},
     * qui ne doit alors ni resauvegarder ni rien écraser.
     */
    public boolean consume(UUID player) {
        return handedOff.remove(player);
    }

    /** Filet : un joueur transféré qui revient ici doit repartir d'une marque propre. */
    public void clear(UUID player) {
        handedOff.remove(player);
    }
}
