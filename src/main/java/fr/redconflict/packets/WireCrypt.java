package fr.redconflict.packets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Brouillage de la CHARGE UTILE des paquets custom, en plus de leur identifiant.
 *
 * <p><b>Le problème que ça vise : le dump.</b> {@link WireIds} permute les
 * numéros de paquets, ce qui oblige à refaire l'inventaire du protocole à chaque
 * version. Mais la charge utile, elle, reste en clair : un outil branché sur la
 * pile réseau du client — quelques lignes de Netty, ou un simple
 * {@code -javaagent} — enregistre les octets tels quels, et le contenu se lit à
 * l'œil. Noms de joueurs, montants, identifiants d'objets : tout est là, et une
 * fois le format compris, forger un paquet ne coûte plus rien.
 *
 * <p><b>Ce que cette couche ajoute.</b> Chaque paquet part chiffré par un flux
 * dérivé d'un secret partagé et d'un aléa propre au paquet, et scellé par un
 * MAC tronqué. Trois conséquences :
 * <ul>
 *   <li>un dump passif ne donne que du bruit : il faut d'abord extraire le
 *       secret du jar obfusqué, à refaire à chaque publication ;</li>
 *   <li>deux paquets identiques ne se ressemblent pas sur le fil, donc on ne
 *       peut plus reconnaître « le paquet d'achat » à sa forme ;</li>
 *   <li>un paquet fabriqué ou modifié est rejeté avant lecture : le serveur ne
 *       traite plus que ce que son propre client a produit.</li>
 * </ul>
 *
 * <p><b>Ce que ça n'est pas.</b> Ce n'est pas de la cryptographie au sens
 * sérieux : la clé est dans le client, donc sur la machine du joueur, et qui
 * sait la retrouver déchiffre tout. C'est un coût de rétro-ingénierie, pas une
 * garantie — exactement la même nature que {@link WireIds}, et pour la même
 * raison : les outils de triche sont PARTAGÉS, donc ce qui compte est de rendre
 * le travail à refaire à chaque version.
 *
 * <p><b>Graine vide = couche inactive</b>, les octets passent tels quels. C'est
 * le coupe-circuit, et c'est aussi la valeur livrée : la couche ne s'active que
 * lorsque Core et client sont publiés ENSEMBLE avec la même graine. Un seul des
 * deux armé rend tout le protocole custom muet.
 *
 * <p><b>Jumeau côté client :</b> {@code net.minecraft.client.custompackets.WireCrypt}.
 * Les deux fichiers doivent rester identiques au bit près — même graine, même
 * format, même MAC.
 */
public final class WireCrypt {

    /**
     * Secret partagé. <b>Vide = couche neutre.</b> À changer à chaque version
     * publiée, et à reporter à l'identique dans le {@code WireCrypt} du client.
     */
    private static final String SEED = "";

    /** Aléa par paquet : 8 octets, assez pour qu'aucun flux ne se répète. */
    private static final int NONCE = 8;

    /** MAC tronqué : 4 octets suffisent à rendre la forgerie non rentable. */
    private static final int TAG = 4;

    private static final byte[] KEY = deriveKey();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WireCrypt() { }

    /** La couche est-elle armée ? */
    public static boolean active() {
        return KEY != null;
    }

    /**
     * Scelle un paquet sortant : {@code nonce | MAC | charge chiffrée}.
     *
     * <p>Renvoie la charge inchangée si la couche est inactive, ou si le
     * chiffrement échoue — mieux vaut un paquet lisible qu'un paquet perdu.
     */
    public static byte[] seal(byte[] payload) {
        if (KEY == null || payload == null || payload.length == 0) {
            return payload;
        }
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            byte[] cipher = xorKeystream(nonce, payload, 0, payload.length);
            byte[] tag = tag(nonce, cipher);

            byte[] out = new byte[NONCE + TAG + cipher.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(tag, 0, out, NONCE, TAG);
            System.arraycopy(cipher, 0, out, NONCE + TAG, cipher.length);
            return out;
        } catch (Throwable ignored) {
            return payload;
        }
    }

    /**
     * Ouvre un paquet entrant.
     *
     * @return la charge utile, ou {@code null} si le MAC ne colle pas — c'est-à-dire
     *         si le paquet a été fabriqué, modifié, ou produit par un client qui
     *         n'a pas la même graine.
     */
    public static byte[] open(byte[] wire) {
        if (KEY == null) {
            return wire;
        }
        if (wire == null || wire.length < NONCE + TAG) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE];
            System.arraycopy(wire, 0, nonce, 0, NONCE);
            byte[] cipher = new byte[wire.length - NONCE - TAG];
            System.arraycopy(wire, NONCE + TAG, cipher, 0, cipher.length);

            byte[] expected = tag(nonce, cipher);
            int diff = 0;
            for (int i = 0; i < TAG; i++) {
                diff |= expected[i] ^ wire[NONCE + i];
            }
            if (diff != 0) {
                return null;
            }
            return xorKeystream(nonce, cipher, 0, cipher.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── Interne ────────────────────────────────────────────────────────────────

    /**
     * Flux de chiffrement : HMAC(clé, nonce || compteur) par blocs de 32 octets.
     *
     * <p>Aucune dépendance, aucun mode de chiffrement à configurer, et un
     * comportement identique bit à bit sur toutes les JVM — ce qui compte plus
     * ici que la performance, puisque le client tourne en Java 8 et le serveur
     * pas forcément.
     */
    private static byte[] xorKeystream(byte[] nonce, byte[] data, int offset, int length)
            throws Exception {
        byte[] out = new byte[length];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        byte[] block = null;
        int blockIndex = -1;
        for (int i = 0; i < length; i++) {
            int wanted = i >> 5; // 32 octets par bloc HMAC-SHA256
            if (wanted != blockIndex) {
                mac.reset();
                mac.update(nonce);
                mac.update((byte) (wanted >>> 24));
                mac.update((byte) (wanted >>> 16));
                mac.update((byte) (wanted >>> 8));
                mac.update((byte) wanted);
                block = mac.doFinal();
                blockIndex = wanted;
            }
            out[i] = (byte) (data[offset + i] ^ block[i & 31]);
        }
        return out;
    }

    private static byte[] tag(byte[] nonce, byte[] cipher) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        mac.update(nonce);
        mac.update(cipher);
        return mac.doFinal();
    }

    private static byte[] deriveKey() {
        if (SEED == null || SEED.length() == 0) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(SEED.getBytes("UTF-8"));
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            return null;
        }
    }
}
