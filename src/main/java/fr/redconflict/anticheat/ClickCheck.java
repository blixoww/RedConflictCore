package fr.redconflict.anticheat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cadence de clics (autoclick), comptée sur les paquets eux-mêmes.
 *
 * <h2>Pourquoi l'ancien contrôle ne voyait pas un autoclick à 22 CPS</h2>
 *
 * <p>Il comptait deux choses, et aucune n'était le clic :
 * <ul>
 *   <li><b>Les coups portés</b>, via {@code EntityDamageByEntityEvent}. Or en
 *       1.8 une cible frappée est invulnérable dix ticks : les coups qui
 *       tombent pendant ce temps ne produisent <i>aucun</i> événement. Contre un
 *       joueur, on plafonnait donc à deux ou trois « coups » par seconde, quelle
 *       que soit la vitesse de clic — le plafond de 16 était inatteignable.</li>
 *   <li><b>Les clics dans le vide</b>, via {@code LEFT_CLICK_AIR}. Le serveur ne
 *       le déclenche que si le tracé de 4,5 blocs ne rencontre aucun bloc : en
 *       combat, dès qu'on regarde un peu le sol derrière l'adversaire, le clic
 *       n'existe plus pour ce contrôle. Le compte tombait sous les 20 par
 *       seconde, et le seuil de violations n'était jamais atteint.</li>
 * </ul>
 * <p>L'analyse de régularité, elle, ne pouvait pas marcher côté serveur : le
 * client traite la souris au rythme de ses ticks (20/s) et les paquets arrivent
 * par paquets de 50 ms. Les intervalles mesurés ici sont ceux des ticks, pas
 * ceux du doigt — un automate et une main y sont indiscernables.
 *
 * <h2>Ce qui est compté maintenant</h2>
 *
 * <p>Le paquet de swing ({@code PacketPlayInArmAnimation}), lu directement sur
 * la connexion, avant tout traitement par le jeu. Le client Red Conflict en
 * envoie exactement un par clic gauche — sur un joueur, sur un bloc ou dans le
 * vide (la pénalité de coup manqué du vanilla a été retirée, voir
 * {@code Minecraft.clickMouse}). Le compte par seconde est donc le vrai CPS.
 *
 * <p><b>Le minage est écarté.</b> Maintenir le clic sur un bloc envoie un swing
 * par tick, soit 20 par seconde parfaitement réguliers : tout mineur passerait
 * pour un autoclick. Tout swing qui suit de près un paquet de minage
 * ({@code PacketPlayInBlockDig} : début, fin ou abandon de casse) est ignoré.
 * La fenêtre couvre aussi les cinq ticks de pause entre deux blocs, pendant
 * lesquels le client continue de balancer le bras.
 *
 * <h2>Deux verdicts, tous deux sur une durée</h2>
 * <ol>
 *   <li><b>Au-dessus du plafond, plusieurs secondes de suite</b>
 *       ({@code max-cps} pendant {@code max-cps-seconds}). Un drag-click humain
 *       dépasse 20 sur une seconde ou deux, il ne tient pas.</li>
 *   <li><b>Cadence haute tenue longtemps</b> ({@code sustained-cps} pendant
 *       {@code sustained-seconds}) : l'automate réglé sous le plafond.</li>
 * </ol>
 * <p>Chaque verdict est en soi une preuve — une série tenue, pas un écart isolé
 * — d'où une alerte immédiate plutôt qu'un compteur de violations à remplir.
 * Une seconde sous le seuil, ou deux secondes sans clic, remettent la série à
 * zéro.
 *
 * <p>{@code anticheat.debug: true} journalise le CPS mesuré de chaque seconde
 * de clic : c'est l'outil pour calibrer avec un vrai joueur.
 */
public final class ClickCheck implements Listener {

    private static final Logger LOG = Logger.getLogger("AntiCheat");

    /** Nom de notre étage dans la chaîne Netty du joueur. */
    private static final String HANDLER = "rc_click_counter";

    private static final long WINDOW_MS = 1000L;

    /**
     * Swings ignorés après un paquet de minage. Couvre la pause de cinq ticks
     * (250 ms) entre deux blocs, plus la gigue réseau.
     */
    private static final long DIG_GRACE_MS = 400L;

    /** Délai minimal entre deux alertes pour un même joueur. */
    private static final long ALERT_COOLDOWN_MS = 15_000L;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    public ClickCheck(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    /** Branche le compteur sur les joueurs déjà connectés (rechargement). */
    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) inject(p);
    }

    /** Débranche tout : un /reload ne doit pas laisser un étage orphelin. */
    public void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) uninject(p);
        states.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    // ══════════════════════════════════════════════════════════
    //  BRANCHEMENT NETTY
    // ══════════════════════════════════════════════════════════
    private void inject(final Player player) {
        final Channel channel = channelOf(player);
        if (channel == null) return;
        final State state = new State(player.getUniqueId(), player.getName());
        states.put(player.getUniqueId(), state);
        channel.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get(HANDLER) != null) pipeline.remove(HANDLER);
                    if (pipeline.get("packet_handler") != null) {
                        pipeline.addBefore("packet_handler", HANDLER, new Counter(state));
                    } else {
                        pipeline.addLast(HANDLER, new Counter(state));
                    }
                } catch (Throwable t) {
                    LOG.warning("[AC] compteur de clics non branché pour "
                            + state.name + " : " + t);
                }
            }
        });
    }

    private void uninject(Player player) {
        final Channel channel = channelOf(player);
        if (channel == null) return;
        channel.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
                } catch (Throwable ignored) { }
            }
        });
    }

    private static Channel channelOf(Player player) {
        try {
            return ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * L'étage Netty : regarde passer les paquets, n'en modifie aucun, et ne
     * laisse jamais une exception remonter dans la connexion du joueur.
     */
    private final class Counter extends ChannelInboundHandlerAdapter {
        private final State state;

        Counter(State state) {
            this.state = state;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof PacketPlayInArmAnimation) {
                    onSwing(state, System.currentTimeMillis());
                } else if (msg instanceof PacketPlayInBlockDig) {
                    PacketPlayInBlockDig.EnumPlayerDigType type = ((PacketPlayInBlockDig) msg).c();
                    if (type == PacketPlayInBlockDig.EnumPlayerDigType.START_DESTROY_BLOCK
                            || type == PacketPlayInBlockDig.EnumPlayerDigType.STOP_DESTROY_BLOCK
                            || type == PacketPlayInBlockDig.EnumPlayerDigType.ABORT_DESTROY_BLOCK) {
                        state.lastDig = System.currentTimeMillis();
                    }
                }
            } catch (Throwable ignored) {
                // Un contrôle ne doit jamais couper la connexion d'un joueur.
            }
            super.channelRead(ctx, msg);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  MESURE (thread Netty du joueur : un seul thread par connexion)
    // ══════════════════════════════════════════════════════════
    private void onSwing(State s, long now) {
        if (now - s.lastDig < DIG_GRACE_MS) return;

        long elapsed = now - s.windowStart;
        if (elapsed >= WINDOW_MS) {
            int cps = s.count;
            // Plus de deux secondes sans clic : la série est rompue, et la
            // « seconde » qu'on fermerait n'en est pas une.
            boolean continuous = elapsed < 2L * WINDOW_MS;
            closeWindow(s, continuous ? cps : 0, now);
            s.windowStart = now;
            s.count = 0;
        }
        s.count++;
    }

    private void closeWindow(State s, int cps, long now) {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)
                || !plugin.getConfig().getBoolean("anticheat.autoclick.enabled", true)) {
            s.overStreak = 0;
            s.sustainedStreak = 0;
            return;
        }

        int maxCps = plugin.getConfig().getInt("anticheat.autoclick.max-cps", 20);
        int maxCpsSeconds = Math.max(1, plugin.getConfig().getInt("anticheat.autoclick.max-cps-seconds", 3));
        int sustainedCps = plugin.getConfig().getInt("anticheat.autoclick.sustained-cps", 13);
        int sustainedSeconds = Math.max(1, plugin.getConfig().getInt("anticheat.autoclick.sustained-seconds", 10));

        s.overStreak = cps > maxCps ? s.overStreak + 1 : 0;
        s.sustainedStreak = cps >= sustainedCps ? s.sustainedStreak + 1 : 0;

        if (cps > 0 && plugin.getConfig().getBoolean("anticheat.debug", false)) {
            LOG.info("[AC] " + s.name + " cps=" + cps
                    + " (au-dessus de " + maxCps + " : " + s.overStreak + " s, >= "
                    + sustainedCps + " : " + s.sustainedStreak + " s)");
        }

        String detail = null;
        if (s.overStreak >= maxCpsSeconds) {
            detail = cps + " CPS, au-dessus de " + maxCps + " pendant " + s.overStreak + " s";
        } else if (s.sustainedStreak >= sustainedSeconds) {
            detail = cps + " CPS, " + sustainedCps + "+ tenus " + s.sustainedStreak + " s";
        }
        if (detail == null || now - s.lastAlert < ALERT_COOLDOWN_MS) return;

        s.lastAlert = now;
        final String message = detail;
        final UUID id = s.id;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    violations.flagConclusive(player, Check.AUTOCLICK, message);
                }
            }
        });
    }

    /** Mesure en cours d'un joueur. Écrite par son seul thread Netty. */
    private static final class State {
        final UUID id;
        final String name;
        volatile long lastDig;
        long windowStart = System.currentTimeMillis();
        int count;
        int overStreak;
        int sustainedStreak;
        long lastAlert;

        State(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
