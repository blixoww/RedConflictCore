package fr.originsfight.db;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sérialise/désérialise une collection d'{@link PotionEffect} en un seul tableau d'octets.
 *
 * <p>Format : {@code int nb} puis pour chaque effet {@code int typeId, int amplifier, int duration,
 * boolean ambient, boolean particles}. Les effets dont le type est inconnu sont ignorés au décodage.
 */
public final class PotionEffectCodec {

    private static final Logger LOG = Logger.getLogger("PlayerDataSync");

    private PotionEffectCodec() {}

    public static byte[] encode(Collection<PotionEffect> effects) {
        if (effects == null) effects = new ArrayList<>();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(effects.size());
            for (PotionEffect e : effects) {
                if (e == null || e.getType() == null) { // slot vide : type 0 + champs nuls
                    out.writeInt(0); out.writeInt(0); out.writeInt(0);
                    out.writeBoolean(false); out.writeBoolean(false);
                    continue;
                }
                out.writeInt(e.getType().getId());
                out.writeInt(e.getAmplifier());
                out.writeInt(e.getDuration());
                out.writeBoolean(e.isAmbient());
                out.writeBoolean(e.hasParticles());
            }
            out.flush();
            return baos.toByteArray();
        } catch (Exception ex) {
            LOG.warning("[Sync] encode effects error: " + ex.getMessage());
            return new byte[0];
        }
    }

    /** Décode une liste d'effets. Retourne une liste vide si {@code data} est vide/invalide. */
    @SuppressWarnings("deprecation")
    public static List<PotionEffect> decode(byte[] data) {
        List<PotionEffect> out = new ArrayList<>();
        if (data == null || data.length == 0) return out;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int n = in.readInt();
            if (n < 0 || n > 256) return out; // garde-fou
            for (int i = 0; i < n; i++) {
                int typeId  = in.readInt();
                int amp     = in.readInt();
                int dur     = in.readInt();
                boolean amb = in.readBoolean();
                boolean par = in.readBoolean();
                PotionEffectType type = PotionEffectType.getById(typeId);
                if (type == null || dur <= 0) continue;
                out.add(new PotionEffect(type, dur, amp, amb, par));
            }
        } catch (Exception ex) {
            LOG.warning("[Sync] decode effects error: " + ex.getMessage());
        }
        return out;
    }
}
