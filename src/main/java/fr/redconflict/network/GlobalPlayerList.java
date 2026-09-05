package fr.redconflict.network;

import fr.redconflict.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tab-list partagé entre les serveurs de la grappe.
 *
 * <p>Objectif : sur le Faction comme sur le Minage, le même tab et le même total.
 * Chaque serveur publie ses connectés dans {@code player_presence} (base H2 déjà
 * commune à la grappe), lit ceux des autres, et les injecte dans le tab de ses
 * propres joueurs.
 *
 * <p><b>Découpage des threads.</b> Tout ce qui touche à Bukkit — pseudo, grade,
 * vanish, ping, envoi des paquets — se fait sur le tick principal, dans
 * {@code PlayerListManager}. Tout ce qui touche à JDBC se fait dans la tâche
 * asynchrone de cette classe. Les deux ne se croisent que sur deux champs
 * {@code volatile} : l'instantané local que le tick dépose, la liste distante que la
 * tâche rapporte. Aucune requête SQL ne passe donc par le thread principal.
 *
 * <p><b>Pourquoi passer par la base et pas par le proxy.</b> Velocity sait qui est
 * connecté, mais le transmettre demanderait un plugin proxy et un canal maison, là
 * où les deux serveurs partagent déjà une base — celle de l'inventaire cross-serveur.
 * Le tab suit le même chemin que le reste de l'état partagé.
 */
public class GlobalPlayerList {

    private final Plugin plugin;
    private final Database db;
    private final PresenceRepository repo;
    private final TabInjector injector;
    private final String serverId;

    /** Ce que ce serveur publie ; posé par le tick sync, lu par la tâche async. */
    private volatile List<NetworkPlayer> local = Collections.emptyList();

    /** Date de cet instantané : au-delà d'un certain âge, on cesse de le republier. */
    private volatile long localAt;

    /** Les connectés des autres serveurs ; posé par la tâche async, lu par le tick sync. */
    private volatile List<NetworkPlayer> remote = Collections.emptyList();

    /** Vue préparée une fois par tick, réutilisée par tous les spectateurs de même rang. */
    private List<TabInjector.Remote> viewPublic = Collections.emptyList();
    private List<TabInjector.Remote> viewStaff = Collections.emptyList();

    /** Lu par la tâche asynchrone, écrit par le thread principal : doit être volatile. */
    private volatile boolean enabled;

    private boolean breakdown;
    private String remoteSuffix;
    private long staleMillis;
    private long purgeMillis;
    private long maxSnapshotAgeMillis;
    private int maxPlayers;
    private final Map<String, String[]> serverLabels = new LinkedHashMap<String, String[]>();

    private int taskId = -1;

    /** Un ménage tous les N cycles : à 2 s de cadence, environ toutes les deux minutes. */
    private static final int PURGE_EVERY = 60;

    private int cycles;

    public GlobalPlayerList(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.repo = new PresenceRepository(db);
        this.injector = new TabInjector(plugin);
        this.serverId = db.getServerId();
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────────

    /** @return {@code true} si le tab partagé tourne (sinon le tab reste purement local). */
    public boolean start() {
        this.enabled = plugin.getConfig().getBoolean("network.tablist.enabled", true);
        if (!enabled) {
            plugin.getLogger().info("[Tab] Tab-list partagé désactivé (network.tablist.enabled: false).");
            return false;
        }
        if (!db.isAvailable()) {
            plugin.getLogger().warning("[Tab] Base H2 indisponible — tab-list partagé désactivé.");
            enabled = false;
            return false;
        }
        if (!repo.createTable()) {
            enabled = false;
            return false;
        }

        int refreshSeconds = Math.max(1, plugin.getConfig().getInt("network.tablist.refresh-seconds", 2));
        int staleSeconds = Math.max(refreshSeconds * 2,
                plugin.getConfig().getInt("network.tablist.stale-seconds", 10));
        this.staleMillis = staleSeconds * 1000L;
        // Le ménage est beaucoup plus large que la fraîcheur : il ne corrige rien
        // (la lecture filtre déjà), il évite juste que la table garde des lignes
        // d'un serveur éteint. Trop court, il effacerait les lignes d'un serveur
        // simplement lent.
        this.purgeMillis = Math.max(60_000L, this.staleMillis * 6);
        this.maxSnapshotAgeMillis = Math.max(10_000L, refreshSeconds * 5000L);
        this.breakdown = plugin.getConfig().getBoolean("network.tablist.breakdown", true);
        this.remoteSuffix = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("network.tablist.remote-suffix", " &8[{tag}&8]"));
        this.maxPlayers = plugin.getConfig().getInt("network.max-players", 0);
        loadServerLabels();

        // Le premier passage attend un tick du tab : publier avant qu'il ait posé
        // son instantané n'écrirait qu'une liste vide.
        long period = refreshSeconds * 20L;
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override public void run() { cycle(); }
        }, period, period).getTaskId();

        plugin.getLogger().info("[Tab] Tab-list partagé actif sur « " + serverId + " » (cycle "
                + refreshSeconds + " s, péremption " + staleSeconds + " s).");
        return true;
    }

    /** Arrête la publication, efface les lignes de ce serveur et les entrées injectées. */
    public void stop() {
        boolean wasEnabled = enabled;
        // Dans cet ordre : le drapeau d'abord, l'effacement ensuite. Un cycle
        // asynchrone déjà lancé republierait sinon les lignes juste après leur
        // suppression, et le serveur voisin garderait des fantômes jusqu'à
        // péremption.
        enabled = false;
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        injector.clearAll();
        if (wasEnabled && db.isAvailable()) {
            // Synchrone, et volontairement : après ce point le pool H2 se ferme.
            repo.clear(serverId);
        }
        remote = Collections.emptyList();
        local = Collections.emptyList();
        viewPublic = Collections.emptyList();
        viewStaff = Collections.emptyList();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── Tâche asynchrone ──────────────────────────────────────────────────────

    private void cycle() {
        if (!enabled || !db.isAvailable()) return;
        long now = System.currentTimeMillis();

        // Un instantané qui n'a pas été rafraîchi n'est plus une information : le tick
        // du tab est tombé (exception, module rechargé). Republier ces lignes ferait
        // vivre des fantômes chez le voisin — on préfère ne rien annoncer.
        List<NetworkPlayer> rows = (now - localAt) > maxSnapshotAgeMillis
                ? Collections.<NetworkPlayer>emptyList()
                : local;

        repo.publish(serverId, rows, now);
        this.remote = Collections.unmodifiableList(repo.fetchOthers(serverId, now - staleMillis));

        // Ménage des serveurs éteints : rare, et à part de la publication. Deux
        // serveurs visant les mêmes lignes mortes au même instant ne doivent pas
        // faire échouer un cycle qui, lui, se passait bien.
        if (++cycles % PURGE_EVERY == 0) repo.purgeStale(now - purgeMillis);
    }

    // ── Côté tick principal ───────────────────────────────────────────────────

    /**
     * Dépose l'état local du tour : ces lignes partiront telles quelles chez les
     * autres serveurs, et c'est ce qui garantit un tab identique des deux côtés.
     */
    public void publishLocal(List<NetworkPlayer> rows) {
        this.local = rows;
        this.localAt = System.currentTimeMillis();
    }

    /**
     * Décrit un joueur local pour la table de présence.
     *
     * <p>{@code display} et {@code sortKey} sont calculés par l'appelant : ce sont
     * exactement le nom et le rang que CE serveur affiche déjà dans son propre tab.
     */
    public NetworkPlayer describe(Player player, String display, String sortKey, boolean hidden) {
        return new NetworkPlayer(player.getUniqueId(), player.getName(), serverId,
                clamp(display, 256), sortKey, hidden, pingOf(player));
    }

    /**
     * Coupe une valeur à la taille de sa colonne.
     *
     * <p>L'insertion se fait en lot, dans une transaction : une seule ligne trop
     * longue ferait échouer la publication ENTIÈRE, et tous les joueurs de ce serveur
     * disparaîtraient du tab du voisin. Un préfixe de grade tronqué est un moindre mal.
     */
    private static String clamp(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * Prépare le tour d'affichage : une vue pour le staff (qui voit les vanish
     * distants), une pour les autres.
     *
     * @param localOnline UUID connectés ici — un joueur qui vient d'arriver sur ce
     *                    serveur ne doit pas apparaître deux fois
     */
    public void beginTick(Set<UUID> localOnline) {
        if (!enabled) return;
        injector.purgeOffline();
        List<NetworkPlayer> snapshot = this.remote;
        this.viewPublic = buildView(snapshot, false, localOnline);
        this.viewStaff = buildView(snapshot, true, localOnline);
    }

    private List<TabInjector.Remote> buildView(List<NetworkPlayer> snapshot, boolean staff,
                                               Set<UUID> localOnline) {
        if (snapshot.isEmpty()) return Collections.emptyList();
        List<TabInjector.Remote> out = new ArrayList<TabInjector.Remote>(snapshot.size());
        for (NetworkPlayer p : snapshot) {
            if (p.isHidden() && !staff) continue;
            // Doublon : il est déjà là pour de vrai. Le serveur qu'il vient de quitter
            // n'a simplement pas encore publié son départ.
            if (localOnline.contains(p.getUuid())) continue;
            out.add(new TabInjector.Remote(p, p.getDisplay() + tagFor(p.getServerId()),
                    TabSorting.teamName(p.getSortKey(), p.getName())));
        }
        return out;
    }

    /** Injecte dans le tab de ce spectateur les joueurs des autres serveurs. */
    public void render(Player viewer, Scoreboard board, boolean viewerIsStaff, Set<UUID> localOnline) {
        if (!enabled) return;
        injector.render(viewer, board, viewerIsStaff ? viewStaff : viewPublic, localOnline);
    }

    /** Nombre de joueurs distants visibles par ce spectateur, au tour préparé. */
    public int remoteCount(boolean viewerIsStaff) {
        if (!enabled) return 0;
        return (viewerIsStaff ? viewStaff : viewPublic).size();
    }

    /**
     * Places affichées dans l'en-tête du tab : {@code network.max-players} s'il est
     * posé, sinon le {@code max-players} de ce serveur.
     */
    public int slots() {
        return maxPlayers > 0 ? maxPlayers : Bukkit.getMaxPlayers();
    }

    /**
     * Répartition par serveur, pour l'en-tête : « Faction » 5, « Minage » 3.
     *
     * @return {@code ""} si la ligne est désactivée ou si aucun autre serveur ne
     *         publie — un seul serveur allumé n'a pas de répartition à montrer
     */
    public String breakdownLine(int localVisible, boolean viewerIsStaff) {
        if (!enabled || !breakdown) return "";
        List<TabInjector.Remote> view = viewerIsStaff ? viewStaff : viewPublic;
        Map<String, Integer> perServer = new LinkedHashMap<String, Integer>();
        perServer.put(serverId, Integer.valueOf(localVisible));
        for (TabInjector.Remote r : view) {
            String id = r.player.getServerId();
            Integer n = perServer.get(id);
            perServer.put(id, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        if (perServer.size() < 2) return "";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : perServer.entrySet()) {
            if (sb.length() > 0) sb.append(" §8| ");
            sb.append(nameFor(e.getKey())).append(" §8: §f").append(e.getValue());
        }
        return sb.toString();
    }

    // ── Étiquettes de serveur ─────────────────────────────────────────────────

    private void loadServerLabels() {
        serverLabels.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("network.servers");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String name = section.getString(id + ".name", id);
            String tag = section.getString(id + ".tag", name);
            serverLabels.put(id.toLowerCase(java.util.Locale.ROOT), new String[]{
                    ChatColor.translateAlternateColorCodes('&', name),
                    ChatColor.translateAlternateColorCodes('&', tag)});
        }
    }

    /** Nom lisible du serveur, ou son identifiant capitalisé s'il n'est pas déclaré. */
    private String nameFor(String id) {
        String[] label = serverLabels.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT));
        if (label != null) return label[0];
        return fallbackName(id);
    }

    /** Suffixe accolé aux joueurs d'un autre serveur, {@code ""} si désactivé. */
    private String tagFor(String id) {
        if (remoteSuffix.isEmpty()) return "";
        String[] label = serverLabels.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT));
        String tag = label != null ? label[1] : fallbackTag(id);
        return remoteSuffix.replace("{tag}", tag);
    }

    private static String fallbackName(String id) {
        if (id == null || id.isEmpty()) return "?";
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /**
     * Étiquette courte par défaut : l'initiale du serveur.
     *
     * <p>Le bloc {@code network.servers} manque forcément sur une grappe déjà
     * installée — {@code config.yml} n'est écrit qu'au premier démarrage. Sans ce
     * repli, chaque joueur distant traînerait « [Minage] » derrière son pseudo au
     * lieu de « [M] », pour une fonctionnalité qui s'active sans rien éditer.
     */
    private static String fallbackTag(String id) {
        if (id == null || id.isEmpty()) return "?";
        return String.valueOf(Character.toUpperCase(id.charAt(0)));
    }

    // ── Sondes Bukkit/NMS ─────────────────────────────────────────────────────

    /**
     * Ping du joueur, lu sur son {@code EntityPlayer}.
     *
     * <p>Bukkit 1.8 n'expose pas la latence : sans ce détour, les joueurs distants
     * s'afficheraient tous à cinq barres. Un échec vaut 0 (barres pleines), jamais
     * une exception.
     */
    private int pingOf(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return handle.getClass().getField("ping").getInt(handle);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
