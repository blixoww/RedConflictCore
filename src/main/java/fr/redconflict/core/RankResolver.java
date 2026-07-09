package fr.redconflict.core;

import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Résolution du grade affiché d'un joueur : préfixe Vault Chat (ou groupe
 * principal), puis PlaceholderAPI ({@code %luckperms_prefix%}) en secours,
 * sinon « Joueur ». Utilisé pour les snapshots et l'envoi au client moddé.
 */
public final class RankResolver {

    public static final String DEFAULT_RANK = "Joueur";

    private RankResolver() {
    }

    public static String resolve(Player player) {
        String rank = fromVaultChat(player);
        if (rank == null) {
            rank = fromPlaceholderApi(player);
        }
        return rank != null ? rank : DEFAULT_RANK;
    }

    private static String fromVaultChat(Player player) {
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp == null) {
                return null;
            }
            Chat chat = rsp.getProvider();
            String prefix = chat.getPlayerPrefix(player);
            if (prefix == null || prefix.isEmpty()) {
                String group = chat.getPrimaryGroup(player);
                if (group != null && !group.isEmpty()) {
                    prefix = group;
                }
            }
            return hasVisibleText(prefix) ? prefix.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** PlaceholderAPI est optionnel : accès par réflexion pour éviter la dépendance dure. */
    private static String fromPlaceholderApi(Player player) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method setPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
            Object out = setPlaceholders.invoke(null, player, "%luckperms_prefix%");
            if (!(out instanceof String)) {
                return null;
            }
            String s = ((String) out).trim();
            if (s.isEmpty() || s.equals("%luckperms_prefix%") || !hasVisibleText(s)) {
                return null;
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return true si la chaîne contient autre chose que des codes couleur. */
    private static boolean hasVisibleText(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        return !s.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim().isEmpty();
    }
}
