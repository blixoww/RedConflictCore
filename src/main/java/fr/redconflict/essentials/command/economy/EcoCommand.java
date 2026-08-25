package fr.redconflict.essentials.command.economy;

import fr.redconflict.core.text.Text;
import fr.redconflict.essentials.command.CommandEnvironment;
import fr.redconflict.essentials.command.EssCommand;
import fr.redconflict.essentials.service.EconomyService;
import fr.redconflict.essentials.service.SeenService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /eco &lt;give|take|set&gt; &lt;joueur&gt; &lt;montant&gt; — administration de l'économie,
 * joueurs hors ligne compris. {@code set} passe par un delta dépôt/retrait
 * pour rester compatible avec n'importe quel provider Vault.
 */
public class EcoCommand extends EssCommand {

    private final EconomyService economy;
    private final SeenService seen;

    public EcoCommand(CommandEnvironment env, EconomyService economy, SeenService seen) {
        super(env, "eco", false, false);
        this.economy = economy;
        this.seen = seen;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.error("Usage : /eco <give|take|set> <joueur> <montant>"));
            return false;
        }
        if (!economy.isAvailable()) {
            sender.sendMessage(Text.error("L'économie est indisponible sur ce serveur."));
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        UUID targetId = seen.resolveUuid(args[1]);

        // Personne de ce nom n'est jamais passé sur le serveur. En offline mode ce n'est pas
        // rédhibitoire : l'UUID se déduit du pseudo, exactement comme le font le vote et la
        // boutique quand ils livrent un joueur déconnecté. Refuser ici ferait disparaître en
        // silence l'argent d'un vote tombé avant la première connexion — le solde vit en base,
        // il n'a pas besoin du joueur.
        boolean jamaisVu = targetId == null;
        if (jamaisVu) {
            if (Bukkit.getOnlineMode()) {
                sender.sendMessage(Text.error("Joueur inconnu : §f" + args[1]));
                return false;
            }
            targetId = offlineUuid(args[1]);
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        String name = target.getName() != null ? target.getName() : args[1];

        // set accepte 0 (remise à zéro d'un solde), give/take exigent un montant positif.
        Double amount = action.equals("set") && args[2].equals("0")
                ? Double.valueOf(0.0) : parseAmount(sender, args[2]);
        if (amount == null) return false;

        boolean success;
        switch (action) {
            case "give":
                success = economy.deposit(target, amount);
                break;
            case "take":
                success = economy.withdraw(target, amount);
                break;
            case "set": {
                double delta = amount - economy.getBalance(target);
                success = delta >= 0 ? economy.deposit(target, delta)
                        : economy.withdraw(target, -delta);
                break;
            }
            default:
                sender.sendMessage(Text.error("Usage : /eco <give|take|set> <joueur> <montant>"));
                return false;
        }

        if (!success) {
            sender.sendMessage(Text.error("Opération refusée (solde insuffisant ?)."));
            return false;
        }
        if (jamaisVu) {
            // Dit, et non tu : un pseudo mal orthographié crédite un compte fantôme, et
            // l'opération a l'air d'avoir réussi.
            sender.sendMessage(Text.info("Aucun passage enregistré pour §f" + name
                    + "§7 : crédité sur son UUID hors-ligne."));
        }
        sender.sendMessage(Text.success("Solde de §f" + name + " §a: §f"
                + economy.format(economy.getBalance(target)) + "§a."));
        return true;
    }

    /**
     * UUID hors-ligne dérivé du pseudo.
     *
     * <p>Le réseau tourne en offline mode : c'est la même dérivation que celle du site
     * ({@code AZURIOM_GAME=mc-offline}) et celle que {@code VoteRewards} utilise déjà pour
     * désigner sa cible. La cible est donc la bonne, même sans passage enregistré.
     */
    @SuppressWarnings("deprecation")
    private static UUID offlineUuid(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String action : Arrays.asList("give", "take", "set")) {
                if (action.startsWith(args[0].toLowerCase(Locale.ROOT))) matches.add(action);
            }
            return matches;
        }
        return null; // complétion joueurs standard
    }
}
