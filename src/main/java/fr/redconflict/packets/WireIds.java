package fr.redconflict.packets;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * Brouillage des identifiants de paquets sur le fil, renouvelé à chaque version.
 *
 * <p><b>Le problème.</b> Les identifiants du protocole sont fixes depuis
 * toujours : {@code 0x62} désigne le défi d'intégrité, {@code 0x14} un achat à
 * l'hôtel des ventes. Qui les a relevés une fois n'a plus jamais à recommencer,
 * et c'est ce qui rend un outil de triche <b>partageable</b> : son auteur fait
 * le travail une seule fois, des centaines de personnes le téléchargent. Face à
 * ce modèle, durcir le client ne rapporte qu'une fois ; changer le protocole
 * rapporte à chaque publication.
 *
 * <p><b>Le principe.</b> Les identifiants logiques ne bougent pas d'une ligne
 * dans le code métier : les 26 fichiers qui envoient des paquets continuent
 * d'écrire {@code 0x53} ou {@code 82}. Seule leur valeur SUR LE FIL est permutée,
 * par une bijection de 0..255 tirée d'une graine propre à la version. La
 * traduction se fait aux seuls points de passage : {@link PacketBuilder#create}
 * à l'envoi, {@link PacketReader#readPacketId()} à la réception.
 *
 * <p><b>Ce que ça ne fait pas.</b> Ce n'est pas du chiffrement : quelqu'un qui
 * observe le trafic en connaissant le jeu finit par reconstituer la table. On
 * achète du temps — et on le rachète à chaque version, ce qui, pour un outil
 * partagé, revient à le tuer.
 *
 * <p><b>Jumeau côté client :</b> {@code net.minecraft.client.custompackets.WireIds}.
 * La graine doit y être IDENTIQUE, et les deux publiés ensemble.
 */
public final class WireIds {

    /**
     * Graine de la permutation. <b>À CHANGER À CHAQUE VERSION PUBLIÉE</b>, et à
     * reporter à l'identique dans le {@code WireIds} du client.
     *
     * <p><b>Chaîne vide = permutation neutre</b>, les identifiants passent tels
     * quels. C'est le coupe-circuit : au moindre doute en production, on vide
     * cette constante des deux côtés et le protocole redevient celui d'avant,
     * sans toucher à une ligne de logique.
     */
    // Brouillage ACTIF. Cette graine DOIT être identique à celle du WireIds du
    // client (net.minecraft.client.custompackets.WireIds), et Core + client
    // doivent être déployés ENSEMBLE. Un seul des deux avec une graine
    // différente rend tout le protocole custom muet (et l'auth avec, via le
    // HUB). La reposer à "" des deux côtés est le coupe-circuit d'urgence.
    private static final String SEED = "rc-wire-2026-08-qss6zcu9q";

    /** Table logique -> fil, et son inverse. */
    private static final int[] TO_WIRE = new int[256];
    private static final int[] FROM_WIRE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            TO_WIRE[i] = i;
        }
        // Garde-fou du coupe-circuit : graine vide = table identité (IDs bruts).
        // Il DOIT rester identique à celui du client — sans lui, seedOf("")
        // produirait une permutation non triviale et « graine vide » ne
        // désactiverait plus rien.
        if (SEED != null && SEED.length() > 0) {
            // java.util.Random est spécifié au bit près : même graine, même
            // suite sur toutes les versions de Java — donc la même table sur le
            // serveur et sur le client, qui tourne en Java 8.
            Random random = new Random(seedOf(SEED));
            for (int i = 255; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int swap = TO_WIRE[i];
                TO_WIRE[i] = TO_WIRE[j];
                TO_WIRE[j] = swap;
            }
        }
        for (int i = 0; i < 256; i++) {
            FROM_WIRE[TO_WIRE[i]] = i;
        }
    }

    private WireIds() { }

    /** Les huit premiers octets de SHA-256(graine), en grand-boutiste. */
    private static long seedOf(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return value;
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            return seed.hashCode();
        }
    }

    /** Identifiant logique -> valeur à écrire sur le fil. */
    public static int toWire(int logical) {
        return (logical & ~0xFF) == 0 ? TO_WIRE[logical] : logical;
    }

    /** Valeur lue sur le fil -> identifiant logique. */
    public static int fromWire(int wire) {
        return (wire & ~0xFF) == 0 ? FROM_WIRE[wire] : wire;
    }
}
