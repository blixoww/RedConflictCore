package fr.originsfight;

/**
 * Centralisateur de tous les messages du plugin RedConflict.
 *
 * COULEURS STANDARD :
 *   §8[§c§lRedConflict§8]  →  préfixe principal
 *   §a  →  succès
 *   §c  →  erreur / danger
 *   §e  →  info / avertissement
 *   §7  →  texte secondaire
 *   §f  →  valeur importante
 *
 * Modifier les constantes ici pour changer les messages partout.
 */
public class RC {

    /** Préfixe principal visible par le joueur. */
    public static final String PRE  = "§8[§c§lRedConflict§8] §r";
    /** Préfixe compact pour les confirmations inline. */
    public static final String PRE_S = "§8| §r";
    /** Séparateur de 32 tirets (tient dans le chat). */
    public static final String SEP  = "§8--------------------------------";

    // ── Génériques ────────────────────────────────────────────────────────────
    public static final String ERR_PLAYER_ONLY = PRE + "§cCette commande est réservée aux joueurs.";
    public static final String ERR_NO_PERM     = PRE + "§cVous n'avez pas la permission d'utiliser cette commande.";
    public static final String ERR_USAGE       = PRE + "§cUsage incorrect.";

    // ── RTP ───────────────────────────────────────────────────────────────────
    public static final String RTP_TELEPORTING = PRE + "§eTéléportation aléatoire en cours§7...";
    public static final String RTP_SUCCESS     = PRE + "§aTéléporté avec succès !";
    public static final String RTP_COOLDOWN    = PRE + "§cVous devez attendre §f%s §cavant de vous retéléporter.";

    // ── Repair ────────────────────────────────────────────────────────────────
    public static final String REPAIR_DONE     = PRE + "§aTous vos items ont été réparés.";
    public static final String REPAIR_NOTHING  = PRE + "§cVous n'avez aucun item à réparer.";
    public static final String REPAIR_COOLDOWN = PRE + "§cRéparation disponible dans §f%s§c.";

    // ── Poubelle ──────────────────────────────────────────────────────────────
    public static final String TRASH_TITLE     = "§8[§c§lPoubelle§8]"; // titre de l'inventaire

    // ── Furnace ───────────────────────────────────────────────────────────────
    public static final String FURNACE_HELP_HEADER = PRE + "§eCommande §f/furnace §8:";
    public static final String FURNACE_HELP_THIS   = "  §8| §f/furnace this §8- §7Cuit l'item que vous tenez.";
    public static final String FURNACE_HELP_ALL    = "  §8| §f/furnace all  §8- §7Cuit tous les items cuisables.";
    public static final String FURNACE_THIS_OK     = PRE + "§aL'item en main a été cuit.";
    public static final String FURNACE_THIS_FAIL   = PRE + "§cL'item en main ne peut pas être cuit.";
    public static final String FURNACE_ALL_OK      = PRE + "§aTous les items cuisables ont été transformés.";
    public static final String FURNACE_ALL_FAIL    = PRE + "§cAucun item cuisable trouvé dans votre inventaire.";

    // ── CombatLog ─────────────────────────────────────────────────────────────
    public static final String CT_IN_COMBAT    = "§cVous êtes en combat ! Attendez §f%s §cavant de vous déconnecter.";
    public static final String CT_NOT_COMBAT   = "§aVous n'êtes pas en combat.";
    public static final String CT_OP           = "§7Vous êtes OP, le combatlog ne vous affecte pas.";

    // ── BottleXP ──────────────────────────────────────────────────────────────
    public static final String BXP_NOT_ENOUGH  = "§cIl vous faut au moins §f10 niveaux §cpour embouteiller votre XP (vous en avez §f%d§c).";
    public static final String BXP_INV_FULL    = "§cInventaire plein ! Faites de la place d'abord.";
    public static final String BXP_SUCCESS     = "§aVous avez embouteillé §e%d niveaux §adans une bouteille d'XP.";
    public static final String BXP_RESTORED    = "§aVous avez récupéré §e%d niveaux §adepuis la bouteille.";

    // ── Trade ─────────────────────────────────────────────────────────────────
    public static final String TRADE_USAGE     = PRE + "§eUsage §f: /trade <joueur> §8| §f/trade accept §8| §f/trade deny";
    public static final String TRADE_SELF      = PRE + "§cVous ne pouvez pas trader avec vous-même.";
    public static final String TRADE_NOT_FOUND = PRE + "§cJoueur introuvable.";
    public static final String TRADE_SENT      = PRE + "§aDemande de trade envoyée à §f%s§a.";
    public static final String TRADE_RECEIVED  = PRE + "§e%s §7vous propose un trade. §f/trade accept §7ou §f/trade deny";
    public static final String TRADE_ACCEPTED  = PRE + "§aTrade accepté avec §f%s§a.";
    public static final String TRADE_DENIED    = PRE + "§cTrade refusé par §f%s§c.";
    public static final String TRADE_NO_REQ    = PRE + "§cVous n'avez aucune demande de trade en attente.";
    public static final String TRADE_ALREADY   = PRE + "§cVous avez déjà une demande de trade en attente.";
    public static final String TRADE_IN_PROG   = PRE + "§cVous êtes déjà en train de trader.";
    public static final String TRADE_CANCELLED = PRE + "§cTrade annulé.";
    public static final String TRADE_CONFIRMED = PRE + "§aLes deux joueurs ont confirmé — échange effectué !";

    // ── GiveAll ───────────────────────────────────────────────────────────────
    public static final String GIVEALL_HINT    = PRE + "§ePlacez les items à distribuer, puis cliquez sur §aEnvoyer§e.";
    public static final String GIVEALL_SENT    = PRE + "§aItems distribués à §f%d §ajoueur(s).";
    public static final String GIVEALL_CANCEL  = PRE + "§cDistribution annulée.";

    // ── Vision (Night Vision) ─────────────────────────────────────────────────
    public static final String VISION_ON       = PRE + "§aVision nocturne §aactivée.";
    public static final String VISION_OFF      = PRE + "§cVision nocturne §cdésactivée.";

    // ── Cobble (anti-cobblestone) ─────────────────────────────────────────────
    public static final String COBBLE_ON       = PRE + "§aFiltrage de la cobblestone §aactivé §7— elle disparaîtra automatiquement.";
    public static final String COBBLE_OFF      = PRE + "§7Filtrage de la cobblestone §cdésactivé§7.";

    // ── Msg (messagerie privée) ───────────────────────────────────────────────
    public static final String MSG_USAGE       = PRE + "§eUsage §f: /msg <joueur> <message>";
    public static final String MSG_NOT_FOUND   = PRE + "§cJoueur introuvable ou hors ligne.";
    public static final String MSG_SELF        = PRE + "§cVous ne pouvez pas vous écrire à vous-même.";
    public static final String MSG_NO_REPLY    = PRE + "§cVous n'avez personne à qui répondre.";
    // Formats : %s1 = expéditeur, %s2 = destinataire, %s3 = message
    public static final String MSG_OUT_FMT     = "§8[§7Vous §8→ §f%s§8] §7%s";   // côté envoyeur
    public static final String MSG_IN_FMT      = "§8[§f%s §8→ §7Vous§8] §7%s";   // côté receveur
    public static final String MSG_SPY_FMT     = "§8[§dSpy §8| §f%s §8→ §f%s§8] §7%s"; // message spy (staff)

    // ── Welcome / Join / Quit ─────────────────────────────────────────────────
    public static final String JOIN_GLOBAL      = "§8[§a+§8] §f%s §7a rejoint §c§lRedConflict§7.";
    public static final String QUIT_GLOBAL      = "§8[§c-§8] §f%s §7a quitté le serveur.";
    public static final String FIRST_JOIN_GLOBAL= "§6§l★ §f%s §7rejoint §c§lRedConflict §7pour la première fois §6§l!";

    // ── Bounty / Prime ─────────────────────────────────────────────────────────
    public static final String BOUNTY_USAGE          = PRE + "§eUsage §f: /prime <joueur> <montant>";
    public static final String BOUNTY_NOT_FOUND      = PRE + "§cJoueur introuvable ou hors ligne.";
    public static final String BOUNTY_SELF           = PRE + "§cVous ne pouvez pas mettre une prime sur vous-même.";
    public static final String BOUNTY_INVALID_AMOUNT = PRE + "§cMontant invalide. Entrez un nombre entier positif.";
    public static final String BOUNTY_ALREADY_PLACED = PRE + "§cVous avez déjà une prime en cours. Attendez qu'elle soit réclamée.";
    public static final String BOUNTY_ALREADY_TARGET = PRE + "§cCe joueur a déjà une prime sur sa tête.";
    public static final String BOUNTY_NO_MONEY       = PRE + "§cVous n'avez pas assez d'argent.";
    public static final String BOUNTY_ECO_ERROR      = PRE + "§cErreur d'économie — contactez un administrateur.";
    public static final String BOUNTY_PLACED         = PRE + "§aPrime placée sur §f%s §apour §f%d$ §a!";
    public static final String BOUNTY_BROADCAST      = SEP + "\n" + PRE + "§6§l\u2620 §e%s §7a placé une prime de §f%d$ §7sur §c%s §7!\n" + SEP;
    public static final String BOUNTY_CLAIMED        = PRE + "§aVous avez réclamé la prime sur §f%s §a! §f+%d$";
    public static final String BOUNTY_CLAIMED_BROADCAST = SEP + "\n" + PRE + "§6§l\u2620 §a%s §7a éliminé §c%s §7et récupère sa prime de §f%d$ §7!\n" + SEP;
    public static final String BOUNTY_EXPIRED_BROADCAST = SEP + "\n" + PRE + "§6§l\u2620 §7Personne n'a réussi à tuer §c%s §7! La prime de §f%d$ §7a expiré.\n" + SEP;

    // ── Loto ──────────────────────────────────────────────────────────────────
    public static final String LOTO_USAGE          = PRE + "§eUsage §f: /loto <montant> §8| §f/loto info §8| §f/loto next §8| §f/loto help";
    public static final String LOTO_START          = SEP + "\n" + PRE + "§6§l\u2B50 §eLoto lancé §7! Vous avez §f2 minutes §7pour parier.\n" + "  §8| §7Tapez §f/loto <montant> §7pour participer !\n" + "  §8| §7Minimum §f3 participants§7, sinon le loto est annulé.\n" + SEP;
    public static final String LOTO_REMINDER       = PRE + "§6§l\u2B50 §eIl reste §f%s §epour parier au Loto ! §f/loto <montant>";
    public static final String LOTO_NOT_OPEN       = PRE + "§cAucun loto n'est en cours. §7Tapez §f/loto next §7pour voir quand le prochain arrive.";
    public static final String LOTO_ALREADY_BET    = PRE + "§cVous avez déjà parié sur ce loto. §7Attendez le tirage !";
    public static final String LOTO_INVALID_AMOUNT = PRE + "§cMontant invalide. Entrez un nombre entier positif.";
    public static final String LOTO_NO_MONEY       = PRE + "§cVous n'avez pas assez d'argent pour ce pari.";
    public static final String LOTO_ECO_ERROR      = PRE + "§cErreur d'économie — contactez un administrateur.";
    public static final String LOTO_BET_OK         = PRE + "§6§l\u2B50 §aPari de §f%d$ §aenregistré ! §7Bonne chance !";
    public static final String LOTO_BET_BROADCAST  = PRE + "§6§l\u2B50 §f%s §7a rejoint le Loto ! §8(§f%d §7participant(s)§8)";
    public static final String LOTO_CANCELLED      = SEP + "\n" + PRE + "§6§l\u2B50 §cLoto annulé §7— seulement §f%d§7/§f%d §7participants. §aMises remboursées.\n" + SEP;
    public static final String LOTO_REFUND         = PRE + "§6§l\u2B50 §eLoto annulé — vous avez été remboursé de §f%d$§e.";
    public static final String LOTO_WIN_BROADCAST  = SEP + "\n" + PRE + "§6§l\u2B50 §a%s §7remporte le Loto et gagne §f%d$ §7!\n" + "  §8| §7%d participants §8| §7Multiplicateur §f%s\n" + SEP;
    public static final String LOTO_WIN_PERSONAL   = PRE + "§6§l\u2B50 §a§lFélicitations ! §aVous avez gagné le Loto ! §f+%d$";
    public static final String LOTO_INFO_CLOSED    = PRE + "§7Aucun loto en cours. Tapez §f/loto next §7pour voir quand le prochain arrive.";
    public static final String LOTO_INFO_CLOSED_NEXT = PRE + "§7Aucun loto en cours. §eProchain loto dans §f%s§e.";
    public static final String LOTO_INFO_OPEN      = PRE + "§6§l\u2B50 §eLoto en cours §7— §f%d §7participant(s), cagnotte §f%d$§7, temps restant §f%s§7.";
    public static final String LOTO_ALREADY_OPEN   = PRE + "§cUn loto est déjà en cours.";
    public static final String LOTO_FORCE_STARTED  = PRE + "§aLoto lancé manuellement.";
    public static final String LOTO_FORCE_STOPPED  = PRE + "§cLoto arrêté manuellement. Mises remboursées.";
    public static final String LOTO_FORCE_STOP     = SEP + "\n" + PRE + "§6§l\u2B50 §cLoto arrêté par un membre du staff. §aMises remboursées.\n" + SEP;
    public static final String LOTO_NEXT           = PRE + "§6§l\u2B50 §7Prochain loto dans §f%s§7.";
    public static final String LOTO_NEXT_IN_PROGRESS = PRE + "§6§l\u2B50 §eUn loto est en cours ! §7Temps restant : §f%s§7. Tapez §f/loto <montant> §7!";
    public static final String LOTO_NEXT_UNKNOWN   = PRE + "§7Le prochain loto sera annoncé prochainement.";

    /** Formate un message en remplaçant %s/%d par les arguments donnés. */
    public static String fmt(String template, Object... args) {
        return String.format(template, args);
    }
}

