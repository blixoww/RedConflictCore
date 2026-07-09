package fr.redconflict.essentials.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sessions /invsee actives (observateur → observé), partagées entre la commande
 * et le listener qui protège l'inventaire en lecture seule.
 */
public class InvseeSessions {

    private final Map<UUID, UUID> viewerToTarget = new HashMap<>();

    public void open(UUID viewer, UUID target) {
        viewerToTarget.put(viewer, target);
    }

    public void close(UUID viewer) {
        viewerToTarget.remove(viewer);
    }

    public boolean isViewing(UUID viewer) {
        return viewerToTarget.containsKey(viewer);
    }
}
