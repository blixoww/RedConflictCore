package fr.redconflict.boutique;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Un article de {@code boutique/boutique.yml}, lu une fois et normalisé.
 *
 * <p>Le YAML décrit chaque article de deux façons selon la catégorie : un grade
 * a une liste {@code commandes}/{@code commandes_perm}, une commande a un
 * singulier {@code commande}/{@code commande_perm}. Cette classe efface la
 * différence pour que le reste du code n'ait plus à la connaître.
 */
public final class BoutiqueItem {

    public final String category;      // grade | cmd | kit | spawner | pack
    public final String id;
    public final String name;          // couleurs Minecraft retirées
    public final String rawName;       // avec les codes couleur, pour l'affichage en jeu
    public final String icon;
    public final List<String> description;
    public final int pricePb;
    public final int pricePbPerm;
    public final long priceMoney;
    public final long priceMoneyPerm;
    public final long durationSeconds;

    /**
     * Nœuds de permission accordés <b>à vie</b> par cet article.
     *
     * <p>C'est ce qui permet de dire « /ec est déjà inclus dans ton grade
     * Immortel » sans coder en dur la moindre hiérarchie : on compare des
     * ensembles de nœuds, pas des noms de grades. Ajouter un grade au YAML suffit
     * donc à ce que le verrou le connaisse.
     *
     * <p>Vide pour les spawners et les packs : ils donnent des objets, pas des
     * droits, et se rachètent autant de fois qu'on veut.
     */
    public final Set<String> nodes;

    /**
     * Commandes de récompense, déjà mises au pluriel : le YAML écrit
     * {@code commande} au singulier pour une commande et {@code commandes} pour
     * un grade, mais le code n'a aucune raison de connaître cette nuance.
     *
     * <p>{@code commandsPerm} peut être vide — kits, spawners et packs ne
     * distinguent pas les deux modes, ce qu'ils donnent est définitif.
     */
    public final List<String> commandsTemp;
    public final List<String> commandsPerm;

    BoutiqueItem(String category, String id, String rawName, String icon, List<String> description,
                 int pricePb, int pricePbPerm, long priceMoney, long priceMoneyPerm,
                 long durationSeconds, Set<String> nodes,
                 List<String> commandsTemp, List<String> commandsPerm) {
        this.category = category;
        this.id = id;
        this.rawName = rawName;
        this.name = stripColor(rawName);
        this.icon = icon;
        this.description = Collections.unmodifiableList(description);
        this.pricePb = pricePb;
        this.pricePbPerm = pricePbPerm;
        this.priceMoney = priceMoney;
        this.priceMoneyPerm = priceMoneyPerm;
        this.durationSeconds = durationSeconds;
        this.nodes = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
        this.commandsTemp = Collections.unmodifiableList(commandsTemp);
        this.commandsPerm = Collections.unmodifiableList(commandsPerm);
    }

    /**
     * Commandes à exécuter pour ce mode d'achat.
     *
     * <p>Un achat à vie sur un article qui n'a pas de version permanente retombe
     * sur les commandes temporaires : c'est le cas des kits et des spawners, dont
     * l'unique liste donne déjà quelque chose de définitif.
     */
    public List<String> commandsFor(boolean permanent) {
        if (permanent && !commandsPerm.isEmpty()) return commandsPerm;
        return commandsTemp;
    }

    /**
     * L'article existe-t-il en deux versions, louée et définitive ?
     *
     * <p>La réponse ne se lit pas dans les prix mais dans les <b>commandes</b> :
     * un grade a une liste {@code commandes_perm} distincte, un pack n'en a pas.
     * C'est ce qui évite un contresens sur les packs, qui déclarent bien un
     * {@code prix_pb_perm} mais n'ont qu'une seule chose à vendre — ils donnent
     * d'emblée un grade à vie, des spawners et du stuff.
     */
    public boolean hasTwoVariants() {
        return !commandsPerm.isEmpty();
    }

    /** Un achat temporaire n'a de sens que s'il existe une version définitive en face. */
    public boolean supportsTemporary() {
        return hasTwoVariants() && durationSeconds > 0 && (pricePb > 0 || priceMoney > 0);
    }

    /**
     * Un article qui accorde un droit se possède une fois pour toutes ; du stuff
     * et des spawners se rachètent. Seuls les premiers passent par le verrou
     * d'appartenance.
     *
     * <p>Les packs en sont exclus <b>même s'ils posent un grade</b> : l'essentiel
     * de ce qu'ils donnent, ce sont des objets et des spawners. Les bloquer parce
     * que le joueur a déjà le grade lui interdirait le reste du lot.
     */
    public boolean isOwnable() {
        if (nodes.isEmpty()) return false;
        return "grade".equals(category) || "cmd".equals(category) || "kit".equals(category);
    }

    /** Prix en PB effectivement demandé pour ce mode d'achat. 0 = indisponible. */
    public int pbPriceFor(boolean permanent) {
        if (!hasTwoVariants()) return pricePb;
        return permanent ? pricePbPerm : pricePb;
    }

    /** Idem en monnaie de jeu. 0 = indisponible dans ce mode. */
    public long moneyPriceFor(boolean permanent) {
        if (!hasTwoVariants()) return priceMoney;
        return permanent ? priceMoneyPerm : priceMoney;
    }

    /** Description en une chaîne, une ligne par entrée — format attendu par la base du site. */
    public String descriptionAsText() {
        StringBuilder sb = new StringBuilder();
        for (String line : description) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(stripColor(line));
        }
        return sb.toString();
    }

    public String nodesAsText() {
        StringBuilder sb = new StringBuilder();
        for (String n : nodes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(n);
        }
        return sb.toString();
    }

    /**
     * Retire les codes couleur Minecraft. Le YAML utilise {@code &}, jamais §,
     * parce qu'il est écrit à la main — mais on gère les deux, un copier-coller
     * depuis le jeu est vite arrivé.
     */
    public static String stripColor(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
