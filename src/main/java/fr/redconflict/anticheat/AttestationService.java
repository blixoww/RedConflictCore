package fr.redconflict.anticheat;

import fr.redconflict.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Défi d'intégrité du client, et vérification de sa réponse.
 *
 * <p><b>Le protocole.</b> Le serveur envoie un aléa de 32 octets. Le client
 * répond par un HMAC-SHA256 de cet aléa, dont la clé est l'empreinte SHA-256 de
 * son propre jar. Le serveur connaît les empreintes acceptées (celles publiées
 * dans le manifeste signé que le launcher vérifie déjà) et recalcule la réponse
 * attendue pour chacune. Un jar modifié, ou des classes chargées depuis un autre
 * jar, produisent une empreinte différente donc une réponse fausse.
 *
 * <p><b>Le silence est la principale défense, pas la cryptographie.</b> Une
 * vérification qui expulse dit au tricheur exactement quoi corriger et lui offre
 * une boucle d'essai-erreur de quelques secondes : il patche, relance, regarde
 * s'il est expulsé. En quelques dizaines d'essais il a gagné. Ici le serveur ne
 * coupe pas, n'affiche rien, ne change aucun comportement observable — il
 * prévient le staff. Le tricheur n'a aucun moyen de savoir si son contournement
 * fonctionne, et doit refaire l'enquête à chaque build du client, dont
 * l'obfuscateur renomme tout.
 *
 * <p><b>Ce que ça n'est pas.</b> Ce n'est pas une preuve cryptographique : la
 * clé est calculée sur la machine du joueur, qui peut en théorie la remplacer
 * par la valeur d'origine. On achète du temps de rétro-ingénierie, pas de
 * l'impossibilité. C'est pourquoi ce contrôle est en alerte, et pourquoi le
 * masquage anti-ESP — qui, lui, ne dépend d'aucune coopération du client —
 * reste la défense qui a un plafond.
 */
public class AttestationService {

    private static final int CHALLENGE_BYTES = 32;
    private static final int PACKET_ATTEST_CHALLENGE = 0x62;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private final SecureRandom random = new SecureRandom();

    /** Défis émis, en attente de réponse. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();

    public AttestationService(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("anticheat.attestation.enabled", false);
    }

    /**
     * Premier défi, après un délai laissant le client finir son initialisation.
     * Les suivants sont replanifiés à intervalle aléatoire par {@link #scheduleNext}.
     */
    public void challenge(final Player player) {
        if (!enabled() || player.hasPermission("redconflict.anticheat.bypass")) {
            return;
        }
        long delay = 20L * Math.max(1, plugin.getConfig().getInt("anticheat.attestation.delay-seconds", 8));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, delay);
    }

    /** Émet un défi immédiatement et arme la surveillance de la réponse. */
    private void send(Player player) {
        if (!enabled()) {
            return;
        }
        byte[] nonce = new byte[CHALLENGE_BYTES];
        random.nextBytes(nonce);
        pending.put(player.getUniqueId(), new Pending(nonce, System.currentTimeMillis()));

        byte[] packet = PacketBuilder.create(PACKET_ATTEST_CHALLENGE)
                .writeVarInt(nonce.length)
                .writeBytes(nonce)
                .buildRaw();
        player.sendPluginMessage(plugin, "CUSTOM:S2C", packet);

        scheduleTimeout(player);
        scheduleNext(player);
    }

    /**
     * Relance un défi à intervalle ALÉATOIRE tant que le joueur est connecté.
     *
     * <p>Un défi unique à la connexion se contourne une fois pour toutes : il
     * suffit de répondre correctement pendant les premières secondes, puis de
     * faire ce qu'on veut. En rejouant le défi à des moments imprévisibles, la
     * réponse doit rester juste en permanence — donc le calcul doit rester
     * intact en permanence, et pas seulement au démarrage.
     *
     * <p>L'aléa sur l'intervalle compte autant que l'aléa sur le nonce. Un
     * intervalle fixe se repère en quelques parties et se contourne en ne
     * trichant pas pendant la fenêtre de contrôle ; un intervalle imprévisible
     * n'offre aucune fenêtre sûre.
     */
    private void scheduleNext(final Player player) {
        int min = Math.max(30, plugin.getConfig().getInt("anticheat.attestation.recheck-min-seconds", 180));
        int max = Math.max(min + 1, plugin.getConfig().getInt("anticheat.attestation.recheck-max-seconds", 900));
        if (!plugin.getConfig().getBoolean("anticheat.attestation.recheck", true)) {
            return;
        }
        long delay = 20L * (min + random.nextInt(max - min));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, delay);
    }


    /**
     * Un client légitime répond en quelques millisecondes. Le silence est donc
     * lui-même un signal — mais un signal faible : une déconnexion, un client
     * lancé depuis un dossier de classes en développement, ou simplement un
     * joueur parti produisent le même silence. D'où un contrôle séparé et un
     * seuil propre.
     */
    private void scheduleTimeout(final Player player) {
        long timeout = 20L * Math.max(1, plugin.getConfig().getInt("anticheat.attestation.timeout-seconds", 15));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending waiting = pending.remove(player.getUniqueId());
            if (waiting == null || !player.isOnline()) {
                return;
            }
            if (plugin.getConfig().getBoolean("anticheat.attestation.flag-silence", true)) {
                violations.flag(player, Check.ATTESTATION, "aucune réponse au défi");
            }
        }, timeout);
    }

    /**
     * Vérifie la réponse reçue. Aucun retour n'est envoyé au client, quel que
     * soit le résultat.
     */
    public void verify(Player player, byte[] answer) {
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null || answer == null || answer.length == 0) {
            return;
        }
        List<String> accepted = plugin.getConfig().getStringList("anticheat.attestation.accepted-jar-sha256");
        if (accepted.isEmpty()) {
            // Aucune empreinte configurée : on ne peut rien conclure, et
            // surtout pas accuser tout le monde.
            return;
        }
        for (String hex : accepted) {
            byte[] key = fromHex(hex);
            if (key == null) {
                continue;
            }
            byte[] expected = hmac(key, waiting.nonce);
            if (expected != null && constantTimeEquals(expected, answer)) {
                return; // client conforme
            }
        }
        violations.flag(player, Check.ATTESTATION, "empreinte du client non reconnue");
    }

    public void forget(UUID player) {
        pending.remove(player);
    }

    // ── Outils ─────────────────────────────────────────────────────────────────

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** Comparaison à temps constant : ne pas donner d'information par la durée. */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static byte[] fromHex(String hex) {
        if (hex == null) {
            return null;
        }
        String clean = hex.trim();
        if (clean.length() % 2 != 0 || clean.isEmpty()) {
            return null;
        }
        try {
            byte[] out = new byte[clean.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Pending {
        private final byte[] nonce;
        private final long sentAt;

        private Pending(byte[] nonce, long sentAt) {
            this.nonce = nonce;
            this.sentAt = sentAt;
        }
    }

}
