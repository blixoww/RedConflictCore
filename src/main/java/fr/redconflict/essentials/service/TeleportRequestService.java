package fr.redconflict.essentials.service;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.config.EssentialsConfig;
import fr.redconflict.essentials.model.TeleportRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demandes /tpa et /tpahere : une seule demande active par cible (la plus récente
 * gagne), expiration après un délai configurable (60 s par défaut), respect du
 * blocage /tpu via {@link TeleportRequestPolicy}. La téléportation acceptée passe
 * par le {@link TeleportService} (délai d'attente + /back + annulation).
 */
public class TeleportRequestService {

    private final EssentialsConfig config;
    private final TeleportService teleports;
    private final TeleportRequestPolicy policy;

    /** uuid de la CIBLE → demande en attente la concernant. */
    private final Map<UUID, TeleportRequest> requestsByTarget = new HashMap<>();

    public TeleportRequestService(EssentialsConfig config, TeleportService teleports,
                                  TeleportRequestPolicy policy) {
        this.config = config;
        this.teleports = teleports;
        this.policy = policy;
    }

    /** Envoie une demande (remplace l'éventuelle demande précédente visant la même cible). */
    public boolean request(Player requester, Player target, TeleportRequest.Type type) {
        if (target.getUniqueId().equals(requester.getUniqueId())) {
            requester.sendMessage(Text.error("Vous ne pouvez pas vous envoyer une demande à vous-même."));
            return false;
        }
        if (policy.isBlocked(requester, target)) {
            requester.sendMessage(Text.error("§f" + target.getName() + " §cbloque les demandes de téléportation."));
            return false;
        }

        requestsByTarget.put(target.getUniqueId(),
                new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), type));

        requester.sendMessage(Text.info("Demande de téléportation envoyée à §f" + target.getName() + "§7..."));
        if (type == TeleportRequest.Type.TO_TARGET) {
            target.sendMessage(Text.info("§f" + requester.getName() + " §7souhaite se téléporter §fvers vous§7."));
        } else {
            target.sendMessage(Text.info("§f" + requester.getName() + " §7souhaite vous téléporter §fà lui§7."));
        }
        target.sendMessage(Text.info("§a/tpyes §7pour accepter§8, §c/tpno §7pour refuser. §8Expire dans "
                + config.tpaExpireSeconds() + "s."));
        return true;
    }

    /** Accepte la demande en attente de {@code target} (s'il y en a une de valide). */
    public void accept(Player target) {
        TeleportRequest request = requestsByTarget.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Text.error("Vous n'avez aucune demande de téléportation en attente."));
            return;
        }
        if (request.isExpired(config.tpaExpireSeconds())) {
            target.sendMessage(Text.error("Cette demande de téléportation a expiré."));
            return;
        }
        final Player requester = Bukkit.getPlayer(request.getRequester());
        if (requester == null || !requester.isOnline()) {
            target.sendMessage(Text.error("Ce joueur s'est déconnecté."));
            return;
        }

        // /tpa : le demandeur bouge ; /tpahere : la cible bouge. La destination est
        // évaluée au moment du départ (position actuelle du point d'ancrage).
        final Player mover = request.getType() == TeleportRequest.Type.TO_TARGET ? requester : target;
        final Player anchor = request.getType() == TeleportRequest.Type.TO_TARGET ? target : requester;

        target.sendMessage(Text.success("Demande de §f" + requester.getName() + " §aacceptée."));
        requester.sendMessage(Text.success("§f" + target.getName() + " §aa accepté votre demande de téléportation."));

        // Celui qui bouge reçoit son compte à rebours via le TeleportService (TP_WARMUP) ;
        // on prévient aussi celui qui attend l'arrivée.
        int delay = teleports.effectiveWarmupSeconds(mover);
        if (delay > 0) {
            anchor.sendMessage(Text.info("§f" + mover.getName() + " §7sera téléporté sur vous dans §c"
                    + delay + "s§7."));
        }
        teleports.delayedTeleport(mover,
                () -> anchor.isOnline() ? anchor.getLocation() : null, null);
    }

    /** Refuse la demande en attente de {@code target}. */
    public void deny(Player target) {
        TeleportRequest request = requestsByTarget.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Text.error("Vous n'avez aucune demande de téléportation en attente."));
            return;
        }
        target.sendMessage(Text.info("Demande de téléportation refusée."));
        Player requester = Bukkit.getPlayer(request.getRequester());
        if (requester != null && requester.isOnline()) {
            requester.sendMessage(Text.error("§f" + target.getName() + " §ca refusé votre demande de téléportation."));
        }
    }

    /** Nettoyage à la déconnexion de la cible. */
    public void clear(UUID target) {
        requestsByTarget.remove(target);
    }
}
