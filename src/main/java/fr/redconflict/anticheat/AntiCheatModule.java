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

    private final Plugin plugin;

    private ViolationTracker violations;
    private ChannelGuard guard;
    private AttestationService attestation;
    private VisibilityCulling visibility;
    private AimCheck aim;
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
        this.visibility = new VisibilityCulling(plugin);

        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)) {
            plugin.getLogger().warning("[AC] Anti-triche DÉSACTIVÉ (anticheat.enabled: false). "
                    + "Le garde des canaux reste actif.");
            registerCleanup();
            return;
        }

        Bukkit.getPluginManager().registerEvents(new MovementCheck(plugin, violations), plugin);
        Bukkit.getPluginManager().registerEvents(new CombatCheck(plugin, violations), plugin);
        Bukkit.getPluginManager().registerEvents(new MiningCheck(plugin, violations), plugin);

        // Visée : le seul contrôle de combat qui ne mesure pas une quantité mais
        // une cohérence. C'est celui que l'aura « discrète » ne peut pas régler
        // pour passer — elle doit changer de nature, pas de seuil.
        this.aim = new AimCheck(plugin, violations);
        Bukkit.getPluginManager().registerEvents(aim, plugin);
        aim.start();

        registerCleanup();

        visibility.start();

        // Décroissance des compteurs : un écart isolé finit par s'effacer, une
        // répétition ne le peut pas.
        long period = 20L * Math.max(1, plugin.getConfig().getInt("anticheat.decay-seconds", 20));
        this.decayTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> violations.decay(), period, period);

        plugin.getLogger().info("[AC] Anti-triche actif — action par défaut : "
                + plugin.getConfig().getString("anticheat.action", "alert") + ".");
    }

    private void registerCleanup() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        if (visibility != null) {
            visibility.stop();
        }
        if (aim != null) {
            aim.stop();
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
    }
}
