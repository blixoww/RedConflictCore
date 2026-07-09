package fr.redconflict.core.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Accès partagé au provider Economy de Vault, résolu paresseusement et mis en
 * cache (le provider est enregistré par le module essentials à l'activation).
 */
public final class VaultEconomy {

    private static Economy cached;

    private VaultEconomy() {
    }

    /** @return le provider Economy, ou {@code null} si Vault ou son provider est absent. */
    public static Economy get() {
        if (cached != null) {
            return cached;
        }
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        cached = rsp != null ? rsp.getProvider() : null;
        return cached;
    }
}
