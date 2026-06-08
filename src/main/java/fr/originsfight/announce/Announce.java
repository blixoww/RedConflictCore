package fr.originsfight.announce;

import java.util.ArrayList;
import java.util.List;

/**
 * Met en forme une annonce serveur : un grand cadre de tirets avec le titre et le message
 * du staff centrés au milieu. Le rendu est identique sur tous les serveurs (le texte complet,
 * déjà formaté, est transmis tel quel via le proxy).
 *
 * <pre>
 *   ------------------------------------------------
 *
 *                      ANNONCE
 *
 *               &lt;message du staff centré&gt;
 *
 *                    - Auteur -
 *
 *   ------------------------------------------------
 * </pre>
 */
public final class Announce {

    /** Largeur de référence (en caractères visibles) pour le cadre et le centrage. */
    private static final int WIDTH = 50;

    private Announce() {}

    /**
     * Construit le bloc d'annonce complet (plusieurs lignes jointes par {@code \n}).
     *
     * @param message message du staff (peut déjà contenir des codes couleur §)
     * @param author  nom à afficher en bas ({@code null}/vide = pas de signature)
     */
    public static String build(String message, String author) {
        String bar = "§c§l" + repeat('-', WIDTH);
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(bar);
        lines.add("");
        lines.add(center("§c§lANNONCE", WIDTH));
        lines.add("");
        for (String l : wrap(message, WIDTH - 6)) {
            lines.add(center("§f§l" + l, WIDTH));
        }
        lines.add("");
        if (author != null && !author.isEmpty()) {
            lines.add(center("§7- " + author + " -", WIDTH));
            lines.add("");
        }
        lines.add(bar);
        lines.add("");
        return String.join("\n", lines);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Centre un texte sur {@code width} caractères visibles (les codes couleur § ne comptent pas). */
    private static String center(String text, int width) {
        int len = visibleLength(text);
        if (len >= width) return text;
        return repeat(' ', (width - len) / 2) + text;
    }

    /** Longueur visible : ignore les séquences §x (code couleur/format). */
    private static int visibleLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '§' && i + 1 < s.length()) {
                i++;
            } else {
                n++;
            }
        }
        return n;
    }

    /** Découpe le message en lignes d'au plus {@code max} caractères visibles, sans couper les mots. */
    private static List<String> wrap(String message, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : message.split("\\s+")) {
            if (word.isEmpty()) continue;
            int projected = visibleLength(line.toString()) + (line.length() == 0 ? 0 : 1) + visibleLength(word);
            if (line.length() > 0 && projected > max) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }
}
