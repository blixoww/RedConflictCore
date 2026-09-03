package fr.redconflict.essentials.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sessions /invsee actives (observateur → observé).
 *
 * <p>La session ne sert plus à interdire les clics — l'inventaire est modifiable
 * depuis que le staff doit pouvoir y déplacer des objets — mais à savoir QUI
 * regarde QUI. C'est ce qui permet de fermer les fenêtres ouvertes sur un joueur
 * qui se déconnecte : sans ça l'observateur continue de manipuler un inventaire
 * fantôme, dont les modifications sont perdues à la sauvegarde du déconnecté et
 * dont les objets sortis, eux, existent bel et bien. C'est une duplication.
 */
public class InvseeSessions {

    private final Map<UUID, UUID> viewerToTarget = new HashMap<UUID, UUID>();

    public void open(UUID viewer, UUID target) {
        viewerToTarget.put(viewer, target);
    }

    public void close(UUID viewer) {
        viewerToTarget.remove(viewer);
    }

    public boolean isViewing(UUID viewer) {
        return viewerToTarget.containsKey(viewer);
    }

    /** Le joueur observé par cet observateur, ou {@code null}. */
    public UUID targetOf(UUID viewer) {
        return viewerToTarget.get(viewer);
    }

    /**
     * Les observateurs actuellement penchés sur cet inventaire.
     *
     * <p>Copie et non vue : l'appelant ferme les fenêtres, ce qui retire des
     * entrées de la table pendant qu'il la parcourt.
     */
    public List<UUID> viewersOf(UUID target) {
        List<UUID> viewers = new ArrayList<UUID>();
        for (Map.Entry<UUID, UUID> entry : viewerToTarget.entrySet()) {
            if (entry.getValue().equals(target)) {
                viewers.add(entry.getKey());
            }
        }
        return viewers;
    }
}
