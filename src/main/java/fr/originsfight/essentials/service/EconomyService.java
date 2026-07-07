package fr.originsfight.essentials.service;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Façade Vault Economy pour les commandes /pay, /eco et /money.
 *
 * <p>Passe toujours par le provider enregistré dans Vault (le nôtre en temps
 * normal, mais reste compatible avec n'importe quel plugin d'économie).
 * Résolution paresseuse : le provider est cherché au premier usage, après
 * que tous les plugins ont fini de s'enregistrer.
 */
public class EconomyService {

    private Economy economy;

    /** @return le provider Vault, ou {@code null} si aucun n'est disponible. */
    public Economy economy() {
        if (economy == null) {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = rsp != null ? rsp.getProvider() : null;
        }
        return economy;
    }

    public boolean isAvailable() {
        return economy() != null;
    }

    public double getBalance(OfflinePlayer player) {
        Economy eco = economy();
        return eco != null ? eco.getBalance(player) : 0.0;
    }

    public boolean has(OfflinePlayer player, double amount) {
        Economy eco = economy();
        return eco != null && eco.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy eco = economy();
        if (eco == null) return false;
        EconomyResponse response = eco.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Economy eco = economy();
        if (eco == null) return false;
        EconomyResponse response = eco.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    public String format(double amount) {
        Economy eco = economy();
        return eco != null ? eco.format(amount) : String.format("%.2f", amount);
    }
}
