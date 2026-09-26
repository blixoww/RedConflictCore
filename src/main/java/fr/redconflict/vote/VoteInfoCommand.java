package fr.redconflict.vote;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.command.CoreCommand;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /vote [joueur]} — le compteur de votes, la progression vers le palier,
 * et ce qu'un vote peut rapporter.
 *
 * <p>Tout est lu dans {@code vote/recompenses.yml}, la même table que celle du
 * tirage : les pourcentages affichés sont donc ceux qui s'appliquent vraiment,
 * sans liste à tenir à jour à côté. Un {@code /rcvote reload} les met à jour.
 *
 * <p>Ouverte à tous, contrairement à {@code /rcvote}, qui distribue.
 */
public class VoteInfoCommand extends CoreCommand {

    private static final int BAR = 20;

    private final VoteRewards rewards;
    private final VoteStorage storage;
    private final VoteMenu menu;

    public VoteInfoCommand(RedConflictCore plugin, VoteRewards rewards, VoteStorage storage, VoteMenu menu) {
        super(plugin, "vote", false);
        this.rewards = rewards;
        this.storage = storage;
        this.menu = menu;
    }

    @Override
    protected void execute(final CommandSender sender, String label, String[] args) {
        // /vote <joueur> : le compteur d'un autre. Sinon le sien (console : aucun).
        final String cible;
        final java.util.UUID uuid;
        if (args.length >= 1) {
            Player enLigne = Bukkit.getPlayerExact(args[0]);
            cible = enLigne != null ? enLigne.getName() : args[0];
            uuid = fr.redconflict.core.PlayerIds.resolve(cible);
        } else if (sender instanceof Player) {
            cible = null;
            uuid = ((Player) sender).getUniqueId();
        } else {
            cible = null;
            uuid = null;
        }

        if (uuid == null || !storage.isAvailable()) {
            montrer(sender, cible, -1);
            return;
        }
        // Le compteur vit en H2 : lu hors du thread principal, affiché après.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final int votes = storage.total(uuid);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        if (sender instanceof Player && !((Player) sender).isOnline()) return;
                        montrer(sender, cible, votes);
                    }
                });
            }
        });
    }

    /** Un joueur voit le menu ; la console garde le récapitulatif texte. */
    private void montrer(CommandSender sender, String cible, int votes) {
        if (sender instanceof Player) menu.open((Player) sender, cible, votes);
        else afficher(sender, cible, votes);
    }

    /**
     * @param cible pseudo consulté, ou {@code null} pour soi-même
     * @param votes compteur cumulé, ou -1 s'il n'y a rien à afficher
     */
    private void afficher(CommandSender sender, String cible, int votes) {
        sender.sendMessage(ChatColor.DARK_GRAY + "§m                                              ");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ Récompenses de vote");

        int palier = rewards.palier();
        VoteLot lotPalier = rewards.lotPalier();
        boolean avecPalier = palier > 0 && lotPalier != null;

        // Le compteur en premier : c'est ce que le joueur vient chercher.
        if (votes >= 0) {
            String qui = cible == null ? "Tes votes" : "Votes de " + cible;
            sender.sendMessage(ChatColor.GRAY + qui + " : " + ChatColor.GREEN + "" + ChatColor.BOLD + votes);
            if (avecPalier) progression(sender, votes, palier);
        }

        String lien = rewards.lienVote();
        if (lien != null && !lien.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Vote sur " + ChatColor.WHITE + lien);
        }

        sender.sendMessage("");
        int pb = rewards.pbParVote();
        if (pb > 0) {
            sender.sendMessage(ChatColor.GRAY + "Chaque vote : " + ChatColor.AQUA + "+" + pb + " PB"
                    + ChatColor.GRAY + ", en plus du lot.");
        }

        int tirages = rewards.tirages();
        sender.sendMessage(ChatColor.GRAY + "Chaque vote tire " + ChatColor.WHITE + tirages
                + ChatColor.GRAY + (tirages > 1 ? " lots" : " lot") + " au hasard :");

        int total = rewards.poidsTotal();
        for (VoteLot lot : rewards.lotsAuTirage()) {
            double pct = total > 0 ? lot.poids * 100.0 / total : 0.0;
            sender.sendMessage(ChatColor.DARK_GRAY + " • " + couleur(lot.nom)
                    + ChatColor.DARK_GRAY + " — " + ChatColor.YELLOW + pourcent(pct));
        }

        if (avecPalier) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "Palier de fidélité " + ChatColor.GRAY + "— tous les "
                    + ChatColor.WHITE + palier + ChatColor.GRAY + " votes, en plus du tirage :");
            sender.sendMessage(ChatColor.DARK_GRAY + " • " + couleur(lotPalier.nom));
        }
        fin(sender);
    }

    private void progression(CommandSender joueur, int votes, int palier) {
        int fait = votes % palier;
        int reste = palier - fait;
        int plein = (int) Math.round(fait * (double) BAR / palier);
        StringBuilder barre = new StringBuilder();
        barre.append(ChatColor.GREEN);
        for (int i = 0; i < plein; i++) barre.append('|');
        barre.append(ChatColor.DARK_GRAY);
        for (int i = plein; i < BAR; i++) barre.append('|');

        joueur.sendMessage(ChatColor.GRAY + "Prochain palier : " + barre
                + ChatColor.GRAY + " " + fait + "/" + palier
                + ChatColor.DARK_GRAY + " (encore " + ChatColor.WHITE + reste
                + ChatColor.DARK_GRAY + (reste > 1 ? " votes)" : " vote)"));
    }

    private static void fin(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "§m                                              ");
    }

    private static String couleur(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s).trim();
    }

    /** 14,0 % — 0,5 % — au dixième, virgule française. */
    private static String pourcent(double pct) {
        return String.format(Locale.FRANCE, "%.1f %%", pct);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Un argument : pseudos des connectés (complétion Bukkit par défaut).
        return args.length == 1 ? null : new ArrayList<String>();
    }
}
