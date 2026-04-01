package fr.originsfight.listeners;

import fr.originsfight.RC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Messages de connexion/déconnexion/bienvenue.
 * Personnalisez RC.JOIN_GLOBAL, RC.QUIT_GLOBAL, etc.
 */
public class WelcomeListener implements Listener {

    private static final String SEP = RC.SEP;

    // Lignes de bienvenue (connexion normale)
    private static final String[] WELCOME = {
        SEP,
        "§c§l  Bienvenue sur RedConflict §8!",
        "§7  Serveur §f1.8.9 §7| PvP / Survie",
        "§7  Discord §f: §bdiscord.gg/UMJUnfQq",
        "§7  Tapez §f/commands §7pour voir les commandes.",
        SEP
    };

    // Lignes pour la première connexion (joueur)
    private static final String[] FIRST_WELCOME = {
        SEP,
        "§6§l  Première connexion détectée — Bienvenue §f%s §6§l!",
        "§7  Serveur §f1.8.9 §7| PvP / Survie",
        "§7  Lis les règles avant de jouer.",
        "§7  Discord §f: §bdiscord.gg/UMJUnfQq",
        SEP
    };

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        event.setJoinMessage(null);

        boolean first = !p.hasPlayedBefore();
        Bukkit.broadcastMessage(RC.fmt(first ? RC.FIRST_JOIN_GLOBAL : RC.JOIN_GLOBAL, p.getName()));

        String[] lines = first ? FIRST_WELCOME : WELCOME;
        for (String line : lines)
            p.sendMessage(String.format(line, p.getName()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        Bukkit.broadcastMessage(RC.fmt(RC.QUIT_GLOBAL, event.getPlayer().getName()));
    }
}
