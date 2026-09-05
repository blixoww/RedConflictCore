package fr.redconflict.network;

import java.util.UUID;

/**
 * Un joueur connecté quelque part dans la grappe, vu depuis la table de présence.
 *
 * <p>Objet immuable : il traverse la frontière des threads (le tick sync du tab le
 * produit, la tâche async l'écrit en base, le tick sync suivant le relit) et rien
 * ne doit pouvoir le modifier en route.
 *
 * <p>La ligne du tab ({@link #display}) est calculée par le serveur d'ORIGINE, pas
 * par celui qui l'affiche : le grade d'un joueur du Minage vient du LuckPerms du
 * Minage. C'est la seule façon d'obtenir le même tab des deux côtés, y compris
 * quand un serveur a des grades ou un anonymat que l'autre ignore.
 */
public final class NetworkPlayer {

    private final UUID uuid;
    private final String name;
    private final String serverId;
    private final String display;
    private final String sortKey;
    private final boolean hidden;
    private final int ping;

    public NetworkPlayer(UUID uuid, String name, String serverId, String display, String sortKey,
                         boolean hidden, int ping) {
        this.uuid = uuid;
        this.name = name;
        this.serverId = serverId;
        this.display = display;
        this.sortKey = sortKey;
        this.hidden = hidden;
        this.ping = ping;
    }

    public UUID getUuid() { return uuid; }

    public String getName() { return name; }

    /** Serveur de la grappe où il est réellement connecté (faction|minage|hub…). */
    public String getServerId() { return serverId; }

    /** Ligne du tab telle que son propre serveur l'affiche : préfixe de grade + pseudo. */
    public String getDisplay() { return display; }

    /** Index de tri du tab ({@code 00_} vanish, {@code 10_} staff, {@code 20_} joueur). */
    public String getSortKey() { return sortKey; }

    /** Vanish ou staffmode : à ne montrer qu'au staff, ici comme sur son serveur. */
    public boolean isHidden() { return hidden; }

    public int getPing() { return ping; }
}
