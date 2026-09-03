package fr.redconflict.core.text;

/**
 * Mesure et alignement du texte dans le chat Minecraft.
 *
 * <p><b>Pourquoi aligner avec des espaces ne marche pas.</b> La police du chat
 * est PROPORTIONNELLE : un {@code i} occupe 2 pixels, un {@code W} en occupe 6,
 * un espace 4. Deux lignes qui ont le même nombre de caractères n'ont donc pas
 * la même longueur à l'écran, et compléter à coups d'espaces pour « faire une
 * colonne » produit un décalage qui grandit avec la différence de contenu. C'est
 * ce qui rend les tirets d'une liste de commandes irréguliers.
 *
 * <p>Ici on compte en PIXELS, et le rembourrage mélange espaces normaux (4 px)
 * et espaces gras (5 px) : toute largeur au-delà de 12 pixels se compose alors
 * exactement, et la colonne tombe au pixel — pas « à peu près ».
 *
 * <p>Les codes {@code §} ne sont pas comptés : ils ne dessinent rien. Le gras,
 * lui, élargit chaque glyphe d'un pixel et est donc suivi.
 */
public final class ChatFont {

    /** Largeur utile de la fenêtre de chat par défaut, en pixels. */
    public static final int CHAT_WIDTH = 320;

    /** Avance d'un espace : glyphe de 3 px plus 1 px d'espacement. */
    public static final int SPACE = 4;

    /** Avance d'un espace en gras : le gras élargit chaque glyphe d'un pixel. */
    public static final int BOLD_SPACE = 5;

    private ChatFont() { }

    /**
     * Largeur d'affichage d'un texte, en pixels.
     *
     * <p>Les codes couleur sont ignorés ; {@code §l} passe en gras (chaque glyphe
     * compte un pixel de plus) et une couleur ou {@code §r} le coupe, exactement
     * comme le fait le client.
     */
    public static int width(String text) {
        if (text == null) {
            return 0;
        }
        int px = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || (code >= '0' && code <= '9')
                        || (code >= 'a' && code <= 'f')) {
                    bold = false; // une couleur remet la mise en forme à zéro
                }
                continue;
            }
            int glyph = glyphWidth(c);
            if (glyph == 0) {
                continue;
            }
            px += glyph + 1 + (bold ? 1 : 0);
        }
        return px;
    }

    /**
     * Complète le texte jusqu'à {@code targetPx} exactement.
     *
     * <p><b>Pourquoi ce n'est pas qu'une boucle d'espaces.</b> Un espace avance
     * de 4 pixels : en n'utilisant que lui, on ne peut viser qu'un multiple de 4
     * et il reste jusqu'à 3 pixels d'erreur — assez pour qu'une colonne de
     * tirets ondule visiblement. Un espace en GRAS, lui, avance de 5. Avec les
     * deux, toute largeur à partir de 12 pixels se compose exactement
     * ({@code n = 4a + 5b}), et la colonne tombe au pixel.
     *
     * <p>Les espaces gras sont fermés par un {@code §r} : le gras ne déborde
     * jamais sur ce que l'appelant écrit ensuite. Comme {@code §r} remet aussi
     * la couleur par défaut, le texte qui suit doit poser la sienne — ce que
     * fait n'importe quelle ligne colorée.
     *
     * <p>Un texte déjà plus large que la cible est renvoyé tel quel : on ne
     * tronque jamais une commande pour faire joli, on laisse la ligne dépasser
     * et l'auteur du message le voit.
     */
    public static String padTo(String text, int targetPx) {
        int missing = targetPx - width(text);
        if (missing < SPACE) {
            return text;
        }
        // On retire des espaces gras (5 px) jusqu'à tomber sur un multiple de 4,
        // que les espaces normaux complètent. La boucle s'arrête d'elle-même sur
        // les rares largeurs non composables (1, 2, 3, 6, 7, 11), au pixel près
        // en dessous.
        int bold = 0;
        while (missing % SPACE != 0 && missing >= BOLD_SPACE) {
            missing -= BOLD_SPACE;
            bold++;
        }
        StringBuilder out = new StringBuilder(text);
        if (bold > 0) {
            out.append("§l");
            for (int i = 0; i < bold; i++) {
                out.append(' ');
            }
            out.append("§r");
        }
        for (int px = 0; px + SPACE <= missing; px += SPACE) {
            out.append(' ');
        }
        return out.toString();
    }

    /** Préfixe le texte d'assez d'espaces pour le centrer sur {@code framePx}. */
    public static String center(String text, int framePx) {
        StringBuilder out = new StringBuilder();
        for (int px = (framePx - width(text)) / 2; px >= SPACE; px -= SPACE) {
            out.append(' ');
        }
        return out.append(text).toString();
    }

    /**
     * Trait plein de la largeur demandée, dans la couleur donnée.
     *
     * <p>Des espaces barrés ({@code §m}), pas une suite de tirets : Minecraft
     * dessine alors une ligne continue, sans les trous qu'un {@code -} répété
     * laisse entre les glyphes, et la largeur se règle au pixel.
     */
    public static String bar(String color, int widthPx) {
        StringBuilder out = new StringBuilder(color).append("§m");
        for (int px = 0; px + SPACE <= widthPx; px += SPACE) {
            out.append(' ');
        }
        return out.toString();
    }

    /**
     * Largeur du glyphe seul, hors espacement, dans la police par défaut de la
     * 1.8 (table {@code DefaultFontInfo}). Un caractère inconnu vaut 5, la
     * largeur courante.
     */
    private static int glyphWidth(char c) {
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
