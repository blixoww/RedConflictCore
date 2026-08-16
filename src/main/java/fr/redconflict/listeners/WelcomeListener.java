package fr.redconflict.listeners;

import fr.redconflict.core.text.RC;
import fr.redconflict.core.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Messages de connexion/déconnexion globaux et écran de bienvenue
 * (variante enrichie à la première connexion).
 */
public class WelcomeListener implements Listener {

    private static final String DISCORD_URL = "N6s4CA84XK";

    private static final String[] WELCOME = {
            RC.SEP,
            "§c§l  Bienvenue sur RedConflict §8!",
            "§7  Serveur §f1.8.9 §7| PvP / Survie",
            "§7  Discord §f: §bdiscord.gg/" + DISCORD_URL,
            "§7  Tapez §f/commands §7pour voir les commandes.",
            RC.SEP
    };

    private static final String[] FIRST_WELCOME = {
            RC.SEP,
            "§6§l  ★ Première connexion ★",
            "§7  Bienvenue §f%s §7sur §c§lRedConflict §7!",
            "§7  Serveur §f1.8.9 §7| PvP / Survie",
            "§7  Lis les règles avant de jouer.",
            "§7  Discord §f: §bdiscord.gg/" + DISCORD_URL,
            RC.SEP
    };

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.setJoinMessage(null);

        boolean first = !player.hasPlayedBefore();
        Bukkit.broadcastMessage(Text.fmt(first ? RC.FIRST_JOIN_GLOBAL : RC.JOIN_GLOBAL, player.getName()));
        for (String line : first ? FIRST_WELCOME : WELCOME) {
            player.sendMessage(String.format(line, player.getName()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        Bukkit.broadcastMessage(Text.fmt(RC.QUIT_GLOBAL, event.getPlayer().getName()));
    }
}
