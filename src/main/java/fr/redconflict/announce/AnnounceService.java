package fr.redconflict.announce;

import fr.redconflict.core.text.Text;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Diffusion d'annonces inter-serveurs via le canal proxy {@code BungeeCord} (compatible Velocity).
 *
 * <p>Émission : {@code Forward → ALL} avec le sous-canal {@value #SUBCHANNEL} portant le texte complet
 * déjà formaté. Le proxy relaie aux AUTRES serveurs ; on diffuse donc aussi localement.
 * <p>Réception : ce listener écoute le canal {@code BungeeCord}, ne traite que le sous-canal
 * {@value #SUBCHANNEL} et ré-affiche le texte tel quel (rendu identique partout).
 */
public class AnnounceService implements PluginMessageListener {

    public static final String SUBCHANNEL = "RC_ANNOUNCE";
    private static final String BUNGEE = "BungeeCord";

    private final RedConflictCore plugin;

    public AnnounceService(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la réception (le canal sortant BungeeCord est déjà enregistré par loadPackets). */
    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE, plugin.getChannelGuard().wrap(this));
        // Filet : s'assure que le canal sortant existe (idempotent si déjà enregistré ailleurs).
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, BUNGEE)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE);
        }
    }

    /** Diffuse l'annonce localement puis la transmet à tous les autres serveurs via le proxy. */
    public void broadcast(String fullText, Player carrier) {
        broadcastLocal(fullText);
        forward(fullText, carrier);
    }

    private void broadcastLocal(String fullText) {
        for (String line : fullText.split("\n", -1)) {
            Bukkit.broadcastMessage(line);
        }
    }

    private void forward(String fullText, Player carrier) {
        // L'envoi via le proxy passe par la connexion d'un joueur du serveur courant.
        if (carrier == null) {
            for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
        }
        if (carrier == null) return; // aucun joueur ici → rien à relayer via le proxy

        try {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            DataOutputStream innerOut = new DataOutputStream(inner);
            innerOut.writeUTF(fullText);
            byte[] data = inner.toByteArray();

            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Forward");
            out.writeUTF("ALL");          // tous les autres serveurs de la grappe
            out.writeUTF(SUBCHANNEL);
            out.writeShort(data.length);
            out.write(data);
            carrier.sendPluginMessage(plugin, BUNGEE, out.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("[Annonce] Échec du relais inter-serveur : " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BUNGEE.equals(channel)) return;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if (!SUBCHANNEL.equals(sub)) return; // autres messages BungeeCord : ignorés
            short len = in.readShort();
            byte[] data = new byte[len];
            in.readFully(data);
            String fullText = new DataInputStream(new ByteArrayInputStream(data)).readUTF();
            broadcastLocal(fullText);
        } catch (Exception e) {
            plugin.getLogger().warning("[Annonce] Lecture de l'annonce échouée : " + e.getMessage());
        }
    }
}
