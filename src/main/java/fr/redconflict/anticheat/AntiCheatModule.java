package fr.redconflict.anticheat;

import fr.redconflict.core.Module;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Module anti-triche : contrôles serveur et garde des canaux du client moddé.
 *
 * <p><b>Ce que ce module peut promettre, et ce qu'il ne peut pas.</b> Tout ce
 * qui est ici s'exécute sur le serveur et juge des faits que le serveur mesure
 * lui-même : une distance, une cadence, une proportion. Un client modifié n'a
 * aucune prise dessus — il ne peut que rester sous les seuils, et rester sous
 * les seuils c'est jouer normalement.
 *
 * <p>C'est la seule catégorie de défense qui tienne. Tout contrôle placé DANS le
 * client tourne sur la machine du joueur, qui possède le processus, le disque et
 * le débogueur : il finira toujours par être retiré. Le durcissement du client
 * et du launcher est un ralentisseur utile contre les injecteurs tout faits,
 * jamais une barrière.
 *
 * <p>Les seuils par défaut sont larges et l'action par défaut est l'alerte. Un
 * anti-triche qui expulse des joueurs honnêtes fait plus de dégâts que le
 * tricheur qu'il attrape ; il faut d'abord observer, puis resserrer.
 */
public class AntiCheatModule implements Module, Listener {

    /** Positions passées, pour la compensation de latence (voir CombatCheck). */
    private PositionHistory positions;

    private final Plugin plugin;

    private ViolationTracker violations;
    private ChannelGuard guard;
    private AttestationService attestation;
    private VisibilityCulling visibility;
    private AimCheck aim;
    private HoneypotCheck honeypot;
    private BreakBurst bursts;
    private FlyCheck fly;
    private BreakSpeedCheck breakSpeed;
    private ContainerCulling containers;
    private NativeGuard natives;
    private BukkitTask decayTask;

    public AntiCheatModule(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "AntiCheat";
    }

    @Override
    public void enable() {
        this.violations = new ViolationTracker(plugin);
        this.guard = new ChannelGuard(plugin, violations);
        this.attestation = new AttestationService(plugin, violations);
        this.natives = new NativeGuard(plugin, violations);
        this.visibility = new VisibilityCulling(plugin);

        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)) {
            plugin.getLogger().warning("[AC] Anti-triche DÉSACTIVÉ (anticheat.enabled: false). "
                    + "Le garde des canaux reste actif.");
            registerCleanup();
            return;
        }

        Bukkit.getPluginManager().registerEvents(new MovementCheck(plugin, violations), plugin);
        // L'historique des positions alimente la compensation de latence de
        // l'allonge. Il s'échantillonne à chaque tick, indépendamment des
        // événements de déplacement.
        positions = new PositionHistory();
        positions.start(plugin);
        Bukkit.getPluginManager().registerEvents(new CombatCheck(plugin, violations, positions), plugin);
        // Mémoire commune des volées de casses (marteau 3×3) : cadence et
        // vitesse de minage jugent un coup, pas neuf blocs. Voir BreakBurst.
        this.bursts = new BreakBurst();
        Bukkit.getPluginManager().registerEvents(new MiningCheck(plugin, violations, bursts), plugin);

        // Fast break : le pendant qualitatif de MiningCheck. Celui-ci compte les
        // blocs par seconde, celui-la verifie que CHAQUE bloc a mis le temps que
        // l'outil tenu impose. Un joueur qui casse un seul bloc trop vite passe
        // sous tous les plafonds de cadence.
        this.breakSpeed = new BreakSpeedCheck(plugin, violations, bursts);
        Bukkit.getPluginManager().registerEvents(breakSpeed, plugin);

        // Vol : echantillonnage par tick et comparaison a la gravite du jeu. Il
        // remplace l'ancien controle de MovementCheck, qui ne comptait qu'une
        // duree en l'air et laissait passer tout vol qui redescend un peu.
        this.fly = new FlyCheck(plugin, violations);
        Bukkit.getPluginManager().registerEvents(fly, plugin);
        fly.start();

        // Visée : le seul contrôle de combat qui ne mesure pas une quantité mais
        // une cohérence. C'est celui que l'aura « discrète » ne peut pas régler
        // pour passer — elle doit changer de nature, pas de seuil.
        this.aim = new AimCheck(plugin, violations);
        Bukkit.getPluginManager().registerEvents(aim, plugin);
        aim.start();

        // Entite fantome : le piege. Tous les controles au-dessus mesurent une
        // grandeur et tolerent une marge, donc laissent passer ce qui se regle
        // juste en dessous. Celui-ci ne mesure rien — il constate qu'un paquet
        // a designe une cible que la boucle de visee du jeu ne pouvait pas
        // designer. C'est le seul dont un declenchement se defend seul.
        this.honeypot = new HoneypotCheck(plugin, violations);
        Bukkit.getPluginManager().registerEvents(honeypot, plugin);
        honeypot.start();

        // Anti chest-ESP : meme principe que le masquage des joueurs, applique
        // aux conteneurs. Le balayage tourne des que le module est actif ; le
        // masquage effectif, lui, attend anticheat.chest-esp.mask.
        this.containers = new ContainerCulling(plugin, violations);
        Bukkit.getPluginManager().registerEvents(containers, plugin);
        containers.start();

        registerCleanup();

        visibility.start();

        // Décroissance des compteurs : un écart isolé finit par s'effacer, une
        // répétition ne le peut pas.
        long period = 20L * Math.max(1, plugin.getConfig().getInt("anticheat.decay-seconds", 20));
        this.decayTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> violations.decay(), period, period);

        plugin.getLogger().info("[AC] Anti-triche actif — action par défaut : "
                + plugin.getConfig().getString("anticheat.action", "alert") + ".");
        announceArmedSanctions();
    }

    /**
     * Journalise les contrôles qui font autre chose qu'alerter.
     *
     * <p>Une sanction automatique se pose en une ligne de configuration et ne se
     * voit ensuite nulle part : rien ne distingue, au démarrage, un serveur qui
     * prévient le staff d'un serveur qui bannit tout seul. Or c'est exactement
     * ce qu'on veut relire avant d'ouvrir — surtout sur les contrôles nourris
     * par le client, où un faux positif bannit des innocents.
     */
    private void announceArmedSanctions() {
        for (Check check : Check.values()) {
            String action = plugin.getConfig().getString(
                    "anticheat." + check.key() + ".action",
                    plugin.getConfig().getString("anticheat.action", "alert"));
            if (action == null || action.trim().isEmpty() || "alert".equalsIgnoreCase(action.trim())) {
                continue;
            }
            plugin.getLogger().warning("[AC] " + check.key() + " : sanction automatique armée — « "
                    + action.trim() + " » au seuil de "
                    + plugin.getConfig().getInt("anticheat." + check.key() + ".threshold", 8) + ".");
        }
    }

    private void registerCleanup() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        if (positions != null) {
            positions.stop();
            positions = null;
        }
        if (visibility != null) {
            visibility.stop();
        }
        if (aim != null) {
            aim.stop();
        }
        if (honeypot != null) {
            honeypot.stop();
            honeypot = null;
        }
        if (fly != null) {
            fly.stop();
            fly = null;
        }
        if (containers != null) {
            containers.stop();
            containers = null;
        }
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    /** Le garde à passer aux poignées de canaux (voir {@code PacketCoreModule}). */
    public ChannelGuard getGuard() {
        return guard;
    }

    public ViolationTracker getViolations() {
        return violations;
    }

    /** Défi d'intégrité du client, pour la poignée du canal et la connexion. */
    public AttestationService getAttestation() {
        return attestation;
    }

    /** Manifeste des bibliothèques natives, pour la poignée du canal. */
    public NativeGuard getNativeGuard() {
        return natives;
    }

    /** Le défi part à la connexion ; le client a quelques secondes pour répondre. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (attestation != null) {
            attestation.challenge(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (violations != null) {
            violations.forget(player.getUniqueId());
        }
        if (guard != null) {
            guard.forget(player.getUniqueId());
        }
        if (attestation != null) {
            attestation.forget(player.getUniqueId());
        }
        if (visibility != null) {
            visibility.forget(player.getUniqueId());
        }
        if (honeypot != null) {
            honeypot.forget(player.getUniqueId());
        }
        if (fly != null) {
            fly.forget(player.getUniqueId());
        }
        if (breakSpeed != null) {
            breakSpeed.forget(player.getUniqueId());
        }
        if (bursts != null) {
            bursts.forget(player.getUniqueId());
        }
        if (containers != null) {
            containers.forget(player.getUniqueId());
        }
        if (natives != null) {
            natives.forget(player.getUniqueId());
        }
    }
}
