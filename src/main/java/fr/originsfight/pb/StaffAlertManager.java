package fr.originsfight.pb;

import fr.originsfight.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Diffuse une alerte aux membres du staff connectés lorsqu'une transaction PB
 * dépasse le seuil configuré (par défaut 500). Permission : staff.pb-alerts.
 *
 * Le seuil est relu dynamiquement à chaque notify() — un /reload ou
 * reloadConfig() suffit pour le mettre à jour sans redémarrage.
 */
public class StaffAlertManager {

    private final RedConflictCore plugin;

    public StaffAlertManager(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    /** Seuil courant (relu depuis config à chaque appel). */
    public int getThreshold() {
        return plugin.getConfig().getInt("pb.alert-threshold", 500);
    }

    /**
     * @param action  "ADD", "REMOVE", "SET", "TRANSFER_OUT", "TRANSFER_IN", "REFUND"
     * @param player  nom du joueur concerné
     * @param amount  montant de la transaction
     * @param reason  raison (ex : "grade:elite", "CMD:Admin")
     */
    public void notify(String action, String player, int amount, String reason) {
        if (amount < getThreshold()) return;

        String sign  = action.equals("ADD")    || action.startsWith("TRANSFER_IN")  || action.equals("REFUND")
                     ? "&a+" : "&c-";
        String label = buildLabel(action);

        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&c&lALERTE PB&8] &7" + label + " &7: " + sign + amount + " PB"
                        + " &7→ &f" + player
                        + " &8| &7Raison : &e" + reason);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("staff.pb-alerts") || p.hasPermission("staff.staff") || p.isOp()) {
                p.sendMessage(msg);
            }
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private static String buildLabel(String action) {
        switch (action) {
            case "ADD":           return "Ajout";
            case "REMOVE":        return "Retrait";
            case "REMOVE_FAIL":   return "Retrait echoue";
            case "SET":           return "Modification solde";
            case "ROLLBACK":      return "Rollback";
            case "REFUND":        return "Remboursement";
            default:
                if (action.startsWith("TRANSFER_OUT")) return "Transfert envoye";
                if (action.startsWith("TRANSFER_IN"))  return "Transfert recu";
                return action;
        }
    }
}
