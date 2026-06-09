package fr.originsfight.announce;

import java.util.ArrayList;
import java.util.List;

/**
 * Met en forme une annonce serveur : un trait plein rouge encadrant le titre et le message
 * du staff, centrés au pixel près. Le rendu est identique sur tous les serveurs (le texte
 * complet, déjà formaté, est transmis tel quel via le proxy).
 *
 * <pre>
 *   ────────────────────────────────────────────────
 *
 *                      ANNONCE
 *
 *               &lt;message du staff centré&gt;
 *
 *                    - Auteur -
 *
 *   ────────────────────────────────────────────────
 * </pre>
 *
 * <p>Le trait est un texte barré ({@code §m}) appliqué à des espaces : Minecraft dessine alors
 * une ligne continue (pas de tirets « à trous »). Sa largeur est calibrée en pixels pour tenir
 * dans la fenêtre de chat par défaut (~320 px) sans déborder ni passer à la ligne.
 */
public final class Announce {

    /** Largeur cible du cadre, en pixels du chat (la fenêtre par défaut fait ~320 px). */
    private static final int FRAME_PX = 308;
    /** Moitié du cadre : repère de centrage horizontal. */
    private static final int CENTER_PX = FRAME_PX / 2;
    /** Avance d'un espace normal (glyphe 3 px + 1 px d'espacement). */
    private static final int SPACE_PX = 4;
    /** Largeur max d'une ligne de message, en pixels (un peu en deçà du cadre). */
    private static final int MESSAGE_MAX_PX = FRAME_PX - 20;

    private Announce() {}

    /**
     * Construit le bloc d'annonce complet (plusieurs lignes jointes par {@code \n}).
     *
     * @param message message du staff (peut déjà contenir des codes couleur §)
     * @param author  nom à afficher en bas ({@code null}/vide = pas de signature)
     */
    public static String build(String message, String author) {
        String bar = "§c§m" + spaces(FRAME_PX / SPACE_PX);
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(bar);
        lines.add("");
        lines.add(center("§c§lANNONCE"));
        lines.add("");
        for (String l : wrap(message, MESSAGE_MAX_PX)) {
            lines.add(center("§f§l" + l));
        }
        lines.add("");
        if (author != null && !author.isEmpty()) {
            lines.add(center("§7- " + author + " -"));
            lines.add("");
        }
        lines.add(bar);
        lines.add("");
        return String.join("\n", lines);
    }

    // ── Centrage / largeur (police Minecraft 1.8 par défaut) ─────────────────────

    /** Préfixe un texte d'assez d'espaces pour le centrer sur le cadre (centrage au pixel). */
    private static String center(String text) {
        int half = pixelWidth(text) / 2;
        int pad = CENTER_PX - half;
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < pad; w += SPACE_PX) sb.append(' ');
        return sb.append(text).toString();
    }

    /** Largeur d'affichage d'un texte en pixels (codes § ignorés, gras pris en compte). */
    private static int pixelWidth(String s) {
        int px = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == 'l') bold = true;
                else if (code == 'r' || (code >= '0' && code <= '9')
                        || (code >= 'a' && code <= 'f')) bold = false; // couleur/reset coupe le gras
                continue;
            }
            int w = charWidth(c);
            if (w == 0) continue;
            px += w + 1 + (bold ? 1 : 0); // +1 d'espacement, +1 de plus en gras
        }
        return px;
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** Découpe le message en lignes d'au plus {@code maxPx} pixels, sans couper les mots. */
    private static List<String> wrap(String message, int maxPx) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : message.split("\\s+")) {
            if (word.isEmpty()) continue;
            int projected = pixelWidth(line.toString())
                    + (line.length() == 0 ? 0 : SPACE_PX) + pixelWidth(word);
            if (line.length() > 0 && projected > maxPx) {
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

    /**
     * Largeur du glyphe (hors espacement) dans la police par défaut de Minecraft 1.8.
     * Valeurs issues de la table standard {@code DefaultFontInfo}. Inconnu → 5 (largeur courante).
     */
    private static int charWidth(char c) {
        switch (c) {
            case ' ': return 3;
            case '!': case '\'': case ',': case '.': case ':': case ';':
            case 'i': case '|': return 1;
            case '`': case 'l': return 2;
            case '"': case '(': case ')': case '*': case 'I': case '[': case ']':
            case 't': case '{': case '}': case '<': case '>': return 3;
            case 'f': case 'k': return 4;
            case '@': case '~': return 6;
            default: return 5;
        }
    }
}
