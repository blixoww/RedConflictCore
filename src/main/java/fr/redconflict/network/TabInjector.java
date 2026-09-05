package fr.redconflict.network;

import com.mojang.authlib.GameProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ajoute au tab-list d'un joueur les connectés des AUTRES serveurs de la grappe.
 *
 * <p>Il n'existe pas d'API Bukkit pour ça : une ligne de tab n'apparaît que si le
 * client a reçu un {@code PacketPlayOutPlayerInfo/ADD_PLAYER}, et Spigot ne sait le
 * construire qu'à partir d'un {@code EntityPlayer} réel. On assemble donc le paquet
 * à la main, avec un {@link GameProfile} fabriqué à partir de la ligne de présence.
 *
 * <p><b>Réflexion.</b> Comme partout ailleurs dans le plugin, les classes
 * {@code net.minecraft.server.<version>} sont chargées par nom : le paquet de version
 * change d'une release à l'autre et le fork maison ne garantit rien de plus.
 * {@code com.mojang.authlib}, lui, n'est pas versionné et s'importe normalement.
 *
 * <p><b>Deux règles de sûreté</b>, parce qu'un paquet mal placé casse le tab RÉEL :
 * <ul>
 *   <li>on n'injecte jamais un UUID connecté ici — sinon le client remplacerait
 *       l'entrée réelle par la fausse (même clé dans sa table) ;</li>
 *   <li>on n'envoie jamais {@code REMOVE_PLAYER} pour un UUID devenu local : la
 *       suppression emporterait l'entrée réelle et le sortirait du tab pour de bon.
 *       On se contente d'oublier la fausse, que le join a déjà écrasée.</li>
 * </ul>
 * Ces deux filtres sont appliqués par l'appelant, qui seul connaît l'état réel du
 * serveur au moment du tick ({@link GlobalPlayerList#render}).
 *
 * <p>En cas d'échec de réflexion, l'injection s'éteint définitivement et le reste
 * du tab partagé (le compteur global) continue de fonctionner : un tab sans les
 * joueurs distants vaut mieux qu'un tab cassé.
 */
public class TabInjector {

    /** Écart de ping à partir duquel on redonne l'info au client. En dessous, invisible. */
    private static final int PING_EPSILON = 30;

    private final Plugin plugin;

    /** Par spectateur : les fausses entrées qu'il a réellement reçues. */
    private final Map<UUID, Map<UUID, Shown>> shown = new HashMap<UUID, Map<UUID, Shown>>();

    private boolean broken;
    private boolean initialized;

    // Cache de réflexion — résolu une fois, au premier rendu.
    private Class<?> packetIface;
    private Constructor<?> packetCtor;
    private Constructor<?> dataCtor;
    private Constructor<?> chatCtor;
    private Field fieldAction;
    private Field fieldEntries;
    private Object actionAdd;
    private Object actionRemove;
    private Object actionDisplayName;
    private Object actionLatency;
    private Object gamemodeSurvival;
    private Object dataOwner;

    public TabInjector(Plugin plugin) {
        this.plugin = plugin;
    }

    /** {@code true} si la réflexion a échoué : plus aucune injection ne sera tentée. */
    public boolean isBroken() {
        return broken;
    }

    /**
     * Met le tab de {@code viewer} à jour pour qu'il montre exactement {@code wanted}.
     *
     * @param wanted      joueurs distants à afficher, déjà filtrés (vanish, doublons
     *                    locaux) et avec leur ligne de tab définitive
     * @param localOnline les UUID réellement connectés ICI, à cet instant
     */
    public void render(Player viewer, Scoreboard board, List<Remote> wanted, Set<UUID> localOnline) {
        if (broken || !init()) return;

        UUID viewerId = viewer.getUniqueId();
        Map<UUID, Shown> current = shown.get(viewerId);
        if (current == null) {
            current = new HashMap<UUID, Shown>();
            shown.put(viewerId, current);
        }

        List<Object> adds = new ArrayList<Object>();
        List<Object> renames = new ArrayList<Object>();
        List<Object> latencies = new ArrayList<Object>();
        List<Object> removes = new ArrayList<Object>();
        Set<UUID> wantedIds = new HashSet<UUID>(wanted.size() * 2);

        try {
            for (Remote r : wanted) {
                wantedIds.add(r.player.getUuid());
                NetworkPlayer p = r.player;
                // Reposée à chaque tour, comme pour les joueurs locaux : le tableau
                // de score d'un spectateur peut avoir été recréé entre-temps, et
                // l'appel ne coûte rien tant que l'appartenance n'a pas bougé.
                assignTeam(board, p.getName(), r.teamName);

                Shown had = current.get(p.getUuid());
                if (had == null) {
                    adds.add(data(new GameProfile(p.getUuid(), p.getName()), p.getPing(), r.display));
                    current.put(p.getUuid(), new Shown(p.getName(), r.display, p.getPing()));
                    continue;
                }
                if (!had.display.equals(r.display)) {
                    renames.add(data(new GameProfile(p.getUuid(), p.getName()), p.getPing(), r.display));
                    had.display = r.display;
                }
                if (Math.abs(had.ping - p.getPing()) >= PING_EPSILON) {
                    latencies.add(data(new GameProfile(p.getUuid(), p.getName()), p.getPing(), r.display));
                    had.ping = p.getPing();
                }
            }

            // Ce qui n'est plus voulu : le joueur distant s'est déconnecté, a changé
            // de serveur, ou est passé vanish pour ce spectateur.
            for (Iterator<Map.Entry<UUID, Shown>> it = current.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Shown> e = it.next();
                if (wantedIds.contains(e.getKey())) continue;
                Shown gone = e.getValue();
                it.remove();
                unassignTeam(board, gone.name);
                if (localOnline.contains(e.getKey())) {
                    // Devenu local : son vrai ADD_PLAYER a déjà remplacé le nôtre.
                    // Un REMOVE ici effacerait l'entrée réelle.
                    continue;
                }
                removes.add(data(new GameProfile(e.getKey(), gone.name), 0, gone.display));
            }

            send(viewer, actionAdd, adds);
            send(viewer, actionDisplayName, renames);
            send(viewer, actionLatency, latencies);
            send(viewer, actionRemove, removes);
        } catch (Throwable t) {
            fail("rendu du tab partagé", t);
        }
    }

    /** Oublie l'état des spectateurs déconnectés — sinon la table grossit sans fin. */
    public void purgeOffline() {
        if (shown.isEmpty()) return;
        Iterator<UUID> it = shown.keySet().iterator();
        while (it.hasNext()) {
            Player p = Bukkit.getPlayer(it.next());
            if (p == null || !p.isOnline()) it.remove();
        }
    }

    /**
     * Retire de tous les tabs les entrées injectées.
     *
     * <p>Appelé quand le tab partagé s'arrête (module désactivé, {@code /red reload})
     * alors que les joueurs, eux, restent connectés : sans ça leurs faux voisins
     * resteraient figés dans le tab jusqu'à leur reconnexion.
     */
    public void clearAll() {
        if (broken || !initialized) {
            shown.clear();
            return;
        }
        for (Map.Entry<UUID, Map<UUID, Shown>> e : shown.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null || !viewer.isOnline()) continue;
            List<Object> removes = new ArrayList<Object>();
            try {
                for (Map.Entry<UUID, Shown> entry : e.getValue().entrySet()) {
                    if (Bukkit.getPlayer(entry.getKey()) != null) continue;
                    removes.add(data(new GameProfile(entry.getKey(), entry.getValue().name), 0,
                            entry.getValue().display));
                    unassignTeam(viewer.getScoreboard(), entry.getValue().name);
                }
                send(viewer, actionRemove, removes);
            } catch (Throwable t) {
                fail("nettoyage du tab partagé", t);
                break;
            }
        }
        shown.clear();
    }

    // ── Teams (tri) ───────────────────────────────────────────────────────────

    private void assignTeam(Scoreboard board, String entry, String teamName) {
        if (board == null) return;
        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);
        // Le préfixe n'est pas reposé ici : la ligne du tab porte déjà le grade
        // complet, et un joueur distant n'a pas de nom au-dessus de la tête.
        Team currentTeam = board.getEntryTeam(entry);
        if (currentTeam == null || !currentTeam.getName().equals(teamName)) {
            team.addEntry(entry);
        }
    }

    private void unassignTeam(Scoreboard board, String entry) {
        if (board == null) return;
        Team team = board.getEntryTeam(entry);
        if (team != null) team.removeEntry(entry);
    }

    // ── Paquets ───────────────────────────────────────────────────────────────

    /** Une entrée de paquet : {@code PacketPlayOutPlayerInfo$PlayerInfoData}. */
    private Object data(GameProfile profile, int ping, String display) throws Exception {
        // La classe interne n'est pas statique : son constructeur réclame l'instance
        // englobante en premier argument. Elle ne sert à rien d'autre — l'entrée ne
        // lit jamais son paquet parent, et c'est celui de send() qui part sur le
        // réseau — donc une instance bidon partagée suffit.
        return dataCtor.newInstance(dataOwner, profile, Integer.valueOf(ping), gamemodeSurvival,
                chatCtor.newInstance(display));
    }

    @SuppressWarnings("unchecked")
    private void send(Player viewer, Object action, List<Object> entries) throws Exception {
        if (entries.isEmpty()) return;
        Object packet = packetCtor.newInstance();
        fieldAction.set(packet, action);
        ((List<Object>) fieldEntries.get(packet)).addAll(entries);

        Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        Object connection = handle.getClass().getField("playerConnection").get(handle);
        connection.getClass().getMethod("sendPacket", packetIface).invoke(connection, packet);
    }

    // ── Réflexion ─────────────────────────────────────────────────────────────

    private boolean init() {
        if (initialized) return true;
        initialized = true;
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            String nms = "net.minecraft.server." + v + ".";

            packetIface = Class.forName(nms + "Packet");
            Class<?> packetClass = Class.forName(nms + "PacketPlayOutPlayerInfo");
            Class<?> dataClass = Class.forName(nms + "PacketPlayOutPlayerInfo$PlayerInfoData");
            Class<?> actionClass = Class.forName(nms + "PacketPlayOutPlayerInfo$EnumPlayerInfoAction");
            Class<?> gamemodeClass = Class.forName(nms + "WorldSettings$EnumGamemode");
            Class<?> chatClass = Class.forName(nms + "ChatComponentText");
            Class<?> componentClass = Class.forName(nms + "IChatBaseComponent");

            packetCtor = packetClass.getConstructor();
            dataCtor = dataClass.getConstructor(packetClass, GameProfile.class, int.class,
                    gamemodeClass, componentClass);
            chatCtor = chatClass.getConstructor(String.class);

            fieldAction = packetClass.getDeclaredField("a");
            fieldEntries = packetClass.getDeclaredField("b");
            fieldAction.setAccessible(true);
            fieldEntries.setAccessible(true);

            actionAdd = enumOf(actionClass, "ADD_PLAYER");
            actionRemove = enumOf(actionClass, "REMOVE_PLAYER");
            actionDisplayName = enumOf(actionClass, "UPDATE_DISPLAY_NAME");
            actionLatency = enumOf(actionClass, "UPDATE_LATENCY");
            gamemodeSurvival = enumOf(gamemodeClass, "SURVIVAL");
            dataOwner = packetCtor.newInstance();
            return true;
        } catch (Throwable t) {
            fail("initialisation NMS", t);
            return false;
        }
    }

    private static Object enumOf(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new IllegalStateException("Constante " + name + " absente de " + type.getName());
    }

    private void fail(String what, Throwable t) {
        broken = true;
        shown.clear();
        plugin.getLogger().warning("[Tab] Injection des joueurs distants désactivée (" + what + ") : " + t);
        plugin.getLogger().warning("[Tab] Le compteur global reste synchronisé ; seules les lignes "
                + "des autres serveurs manqueront dans le tab.");
    }

    // ── Structures ────────────────────────────────────────────────────────────

    /** Un joueur distant prêt à être affiché : ligne finale + team de tri. */
    public static final class Remote {
        final NetworkPlayer player;
        final String display;
        final String teamName;

        public Remote(NetworkPlayer player, String display, String teamName) {
            this.player = player;
            this.display = display;
            this.teamName = teamName;
        }
    }

    /** Ce qu'un spectateur a déjà reçu, pour n'envoyer que les différences. */
    private static final class Shown {
        final String name;
        String display;
        int ping;

        Shown(String name, String display, int ping) {
            this.name = name;
            this.display = display;
            this.ping = ping;
        }
    }
}
