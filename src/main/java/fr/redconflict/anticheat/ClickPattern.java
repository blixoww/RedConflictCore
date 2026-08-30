package fr.redconflict.anticheat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Régularité des clics — la signature qu'un automate ne peut pas effacer.
 *
 * <h2>Pourquoi ce contrôle attrape ce que les autres laissent passer</h2>
 *
 * <p>Tous les autres contrôles mesurent une <b>quantité</b> : combien de blocs,
 * combien de coups par seconde, combien de degrés d'écart. Un tricheur soigneux
 * lit les seuils, se règle en dessous, et devient invisible. C'est exactement ce
 * que fait un autoclick réglé à 10 coups/s quand le plafond est à 16.
 *
 * <p>Celui-ci ne mesure pas une quantité mais une <b>manière</b>. Une main
 * humaine ne produit jamais deux intervalles identiques : le tremblement, la
 * fatigue et le rythme respiratoire donnent un écart-type de l'ordre de 15 à
 * 40 % de l'intervalle moyen. Un automate, lui, produit soit des intervalles
 * constants, soit un tirage aléatoire <i>uniforme</i> dans une plage étroite —
 * dans les deux cas une régularité qu'aucun poignet n'atteint.
 *
 * <p><b>Baisser sa cadence ne l'aide pas.</b> Cliquer plus lentement ne rend pas
 * un automate irrégulier : il devient un automate lent. Pour passer sous ce
 * contrôle, il faut reproduire la distribution d'une main — c'est-à-dire écrire
 * un générateur de bruit crédible, ce qui est un tout autre travail que de
 * baisser un chiffre dans une configuration.
 *
 * <h2>Deux indices, pas un</h2>
 *
 * <ol>
 *   <li><b>Coefficient de variation</b> (écart-type / moyenne). Sous
 *       {@code max-cv}, la cadence est trop régulière pour une main.</li>
 *   <li><b>Intervalles répétés à l'identique.</b> Une main ne rejoue pas deux
 *       fois exactement 91 ms. Un automate, même « randomisé », retombe souvent
 *       sur les mêmes valeurs entières.</li>
 * </ol>
 *
 * <p>Les deux doivent être réunis pour signaler. Un seul suffirait à faire
 * remonter un joueur en pleine série de clics rapides et réguliers — ça arrive,
 * sur quelques secondes.
 *
 * <h2>Ce qui protège des faux positifs</h2>
 *
 * <p>Il faut un échantillon d'au moins {@code min-samples} intervalles avant
 * tout verdict, et la fenêtre est purgée dès que le joueur cesse de frapper une
 * seconde. On juge donc une séquence de combat soutenue, jamais trois clics.
 */
public final class ClickPattern {

    /** Au-delà, on considère que la série de clics est terminée. */
    private static final long GAP_MS = 1200L;

    /** Profondeur d'analyse : au-delà, on juge un combat déjà ancien. */
    private static final int MAX_SAMPLES = 40;

    private final Map<UUID, Deque<Long>> clicks = new ConcurrentHashMap<UUID, Deque<Long>>();

    /** Résultat d'une analyse : les chiffres qui motivent le signalement. */
    public static final class Verdict {
        public final boolean suspicious;
        public final double cv;
        public final int repeats;
        public final int samples;
        Verdict(boolean suspicious, double cv, int repeats, int samples) {
            this.suspicious = suspicious; this.cv = cv;
            this.repeats = repeats; this.samples = samples;
        }
    }

    public void forget(UUID uuid) {
        clicks.remove(uuid);
    }

    /**
     * Enregistre un coup et analyse la série en cours.
     *
     * @param maxCv      coefficient de variation en dessous duquel la cadence
     *                   est jugée mécanique
     * @param maxRepeats nombre d'intervalles identiques toléré
     * @param minSamples taille minimale d'échantillon avant tout verdict
     */
    public Verdict record(UUID uuid, long now, double maxCv, int maxRepeats, int minSamples) {
        Deque<Long> q = clicks.get(uuid);
        if (q == null) {
            q = new ArrayDeque<Long>(MAX_SAMPLES + 1);
            clicks.put(uuid, q);
        }

        // Trou dans la série : on repart de zéro. Un joueur qui reprend le combat
        // après une pause ne doit pas être jugé sur ses clics d'il y a dix
        // secondes, ni bénéficier de leur irrégularité.
        if (!q.isEmpty() && now - q.getLast() > GAP_MS) {
            q.clear();
        }
        q.addLast(now);
        while (q.size() > MAX_SAMPLES) q.removeFirst();

        if (q.size() < minSamples + 1) {
            return new Verdict(false, 0.0D, 0, q.size());
        }

        List<Long> gaps = new ArrayList<Long>(q.size());
        Long prev = null;
        for (Long t : q) {
            if (prev != null) gaps.add(t - prev);
            prev = t;
        }

        double mean = 0.0D;
        for (Long g : gaps) mean += g;
        mean /= gaps.size();
        if (mean <= 0.0D) return new Verdict(false, 0.0D, 0, gaps.size());

        double var = 0.0D;
        for (Long g : gaps) {
            double d = g - mean;
            var += d * d;
        }
        double cv = Math.sqrt(var / gaps.size()) / mean;

        // Intervalles rigoureusement identiques : l'empreinte d'une minuterie.
        int repeats = 0;
        for (int i = 0; i < gaps.size(); i++) {
            for (int j = i + 1; j < gaps.size(); j++) {
                if (gaps.get(i).longValue() == gaps.get(j).longValue()) repeats++;
            }
        }

        boolean suspicious = cv < maxCv && repeats > maxRepeats;
        return new Verdict(suspicious, cv, repeats, gaps.size());
    }
}
