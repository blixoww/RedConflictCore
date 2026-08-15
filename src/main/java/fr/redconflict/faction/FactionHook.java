package fr.redconflict.faction;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Interrupteur unique de l'intégration RedFaction.
 *
 * <p>Tout code qui touche à {@code fr.redfaction.*} doit d'abord vérifier
 * {@link #isEnabled()} : le hook est coupé si {@code hooks.redfaction: false}
 * (config.yml) ou si le plugin RedFaction n'est pas installé — cas du Minage,
 * où le core tourne sans factions.
 *
 * <p><b>Pourquoi c'est indispensable :</b> sans RedFaction dans le classpath,
 * la simple exécution d'une méthode qui manipule un {@code Faction} lève une
 * {@link NoClassDefFoundError} (une {@code Error}, donc non rattrapée par les
 * {@code catch (Exception)} des appelants). Les appels à l'API doivent donc
 * rester isolés dans des méthodes qui ne sont jamais atteintes quand le hook
 * est coupé — cette classe ne référence elle-même aucune classe RedFaction.
 *
 * <p>L'état est figé au démarrage : changer {@code hooks.redfaction} demande un
 * redémarrage du serveur (les senders faction sont installés à l'activation).
 */
public final class FactionHook {

    /** Chemin de la clé de config, également citée dans les logs. */
    private static final String CONFIG_KEY = "hooks.redfaction";

    private static volatile boolean enabled;

    private FactionHook() {
    }

    /** Résout l'état du hook (config + présence du plugin) et le journalise. */
    public static void init(JavaPlugin plugin) {
        boolean allowed = plugin.getConfig().getBoolean(CONFIG_KEY, true);
        boolean present = plugin.getServer().getPluginManager().getPlugin("RedFaction") != null;
        enabled = allowed && present;

        if (!allowed) {
            plugin.getLogger().info("[Faction] Intégration RedFaction désactivée par config ("
                    + CONFIG_KEY + ": false) — aucun appel à l'API faction.");
        } else if (!present) {
            plugin.getLogger().info("[Faction] Plugin RedFaction absent — intégration faction désactivée.");
        }
    }

    /** @return true si l'API RedFaction peut être appelée sur ce serveur. */
    public static boolean isEnabled() {
        return enabled;
    }
}
