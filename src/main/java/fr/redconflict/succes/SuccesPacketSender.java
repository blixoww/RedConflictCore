package fr.redconflict.succes;

import fr.redconflict.RedConflictCore;
import fr.redconflict.packets.PacketBuilder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Envoi des paquets succès, serveur → client moddé.
 *
 * <p>Canal {@code CUSTOM:ACH_S2C}. Identifiants :
 * <pre>
 * 0xF1 ACH_INIT     catalogue complet (une fois, à la connexion)
 * 0xF2 ACH_DATA     avancement complet du joueur
 * 0xF3 ACH_PROGRESS avancement d'un seul succès
 * 0xF4 ACH_UNLOCK   déblocage, pour le bandeau de notification
 * 0xF5 ACH_OPEN     demande d'ouverture du menu
 * </pre>
 *
 * <p><b>Le catalogue part une fois, l'avancement à la demande.</b> Les
 * définitions ne changent pas en cours de partie ; ce qui bouge, c'est
 * l'avancement, et il tient en quelques octets par succès.
 */
public class SuccesPacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:ACH_S2C";

    private static final int PKT_INIT = 0xF1;
    private static final int PKT_DATA = 0xF2;
    private static final int PKT_PROGRESS = 0xF3;
    private static final int PKT_UNLOCK = 0xF4;
    private static final int PKT_OPEN = 0xF5;

    /** {@code CraftPlayer.addChannel}, résolu une fois — voir {@link #ensureChannelOpen}. */
    private static java.lang.reflect.Method ADD_CHANNEL;
    /** L'avertissement ne sert qu'une fois : sinon il inonde la console à chaque envoi. */
    private static boolean channelWarningSent;

    private final RedConflictCore plugin;
    private final SuccesCatalog catalog;
    private final SuccesManager manager;

    public SuccesPacketSender(RedConflictCore plugin, SuccesCatalog catalog, SuccesManager manager) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.manager = manager;
    }

    /** Catalogue complet : identité, rubrique, difficulté, objectif, récompense. */
    public void sendInit(Player player) {
        List<Succes> all = catalog.all();
        PacketBuilder pb = PacketBuilder.create(PKT_INIT);
        pb.writeVarInt(all.size());
        for (Succes succes : all) {
            pb.writeString(succes.id);
            pb.writeString(succes.name);
            pb.writeString(succes.description);
            pb.writeString(succes.category);
            pb.writeVarInt(succes.tier);
            pb.writeVarInt(succes.goal);
            pb.writeString(succes.rewardText);
            pb.writeLong(succes.rewardMoney);
        }
        send(player, pb.build());
    }

    /** Avancement de tous les succès pour ce joueur. */
    public void sendData(Player player) {
        List<Succes> all = catalog.all();
        PacketBuilder pb = PacketBuilder.create(PKT_DATA);
        pb.writeVarInt(all.size());
        for (Succes succes : all) {
            SuccesDatabase.Entry entry = manager.entryOf(player.getUniqueId(), succes.id);
            pb.writeString(succes.id);
            pb.writeVarInt(Math.min(entry.progress, succes.goal));
            pb.writeByte((byte) entry.state());
        }
        send(player, pb.build());
    }

    /** Avancement d'un seul succès — le cas courant, quelques octets. */
    public void sendProgress(Player player, String succesId, SuccesDatabase.Entry entry) {
        send(player, PacketBuilder.create(PKT_PROGRESS)
                .writeString(succesId)
                .writeVarInt(Math.max(0, entry.progress))
                .writeByte((byte) entry.state())
                .build());
    }

    /** Déblocage : le client en fait un bandeau, puis met sa fiche à jour. */
    public void sendUnlock(Player player, Succes succes, SuccesDatabase.Entry entry) {
        send(player, PacketBuilder.create(PKT_UNLOCK)
                .writeString(succes.id)
                .writeString(succes.name)
                .writeString(succes.rewardText)
                .writeVarInt(succes.tier)
                .writeVarInt(Math.max(0, entry.progress))
                .writeByte((byte) entry.state())
                .build());
    }

    /** Demande d'ouverture du menu (commande {@code /succes}). */
    public void sendOpen(Player player) {
        send(player, PacketBuilder.create(PKT_OPEN).build());
    }

    private void send(Player player, byte[] data) {
        ensureChannelOpen(player);
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, data);
    }

    /**
     * Ouvre le canal côté serveur pour ce joueur, avant le premier envoi.
     *
     * <p><b>Le piège.</b> {@code CraftPlayer.sendPluginMessage} ne fait rien —
     * silencieusement, sans exception ni log — si le canal n'est pas dans la
     * liste que le CLIENT a déclarée par son paquet {@code REGISTER} à la
     * connexion. Or cette liste est figée dans le jar du client : tout client
     * déjà déployé ignore {@code CUSTOM:ACH_S2C} et ferait disparaître nos
     * paquets sans laisser la moindre trace.
     *
     * <p>{@code CraftPlayer.addChannel} déclare le canal côté serveur à la
     * place du client. Le client moddé, lui, sait déjà le traiter : son
     * dispatcher connaît le canal, seule la déclaration manquait. Un client qui
     * ne l'écouterait pas ignore le paquet — c'est le comportement prévu par le
     * protocole pour un canal inconnu.
     *
     * <p>Appelé par réflexion : {@code addChannel} vit sur CraftPlayer, pas sur
     * l'interface Bukkit, et on ne veut pas lier ce module à une version de NMS.
     */
    private void ensureChannelOpen(Player player) {
        try {
            if (ADD_CHANNEL == null) {
                ADD_CHANNEL = player.getClass().getMethod("addChannel", String.class);
            }
            ADD_CHANNEL.invoke(player, CHANNEL_S2C);
        } catch (Throwable t) {
            if (!channelWarningSent) {
                channelWarningSent = true;
                plugin.getLogger().warning("[Succes] Impossible d'ouvrir " + CHANNEL_S2C
                        + " côté serveur (" + t.getClass().getSimpleName() + ") : les succès"
                        + " n'arriveront qu'aux clients qui déclarent eux-mêmes ce canal.");
            }
        }
    }
}
