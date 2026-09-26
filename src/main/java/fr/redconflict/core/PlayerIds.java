package fr.redconflict.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pseudo → identité, sans jamais demander à Mojang.
 *
 * <p><b>Pourquoi pas {@code Bukkit.getOfflinePlayer(String)}.</b> Avec
 * {@code bungeecord: true} (on est derrière Velocity), Spigot 1.8 consulte
 * {@code usercache.json}, et pour un pseudo absent du cache — jamais venu, ou
 * entrée expirée au bout d'un mois — il interroge l'API Mojang. Si le pseudo
 * existe chez Mojang, on récupère l'UUID <b>premium</b> de quelqu'un d'autre :
 * PB crédités sur un {@code game_id} qu'Azuriom ne connaît pas, lot mis de côté
 * sous un UUID que le joueur n'aura jamais. Et l'appel réseau bloque le thread
 * principal au passage.
 *
 * <p>Le réseau tourne en offline mode : l'UUID se déduit du pseudo, exactement
 * comme le fait Velocity à la connexion et Azuriom ({@code AZURIOM_GAME=mc-offline})
 * pour {@code users.game_id}. Les trois côtés tombent donc sur la même valeur.
 */
public final class PlayerIds {

    private PlayerIds() { }

    /** UUID offline dérivé du pseudo : {@code uuid3("OfflinePlayer:" + pseudo)}. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Le joueur connecté s'il est là (son UUID est celui de la session), sinon
     * l'UUID offline du pseudo. Jamais de requête réseau.
     */
    public static UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId() : offlineUuid(name);
    }

    /** {@link OfflinePlayer} correspondant, construit par UUID (pas de recherche par nom). */
    public static OfflinePlayer offlinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(offlineUuid(name));
    }
}
