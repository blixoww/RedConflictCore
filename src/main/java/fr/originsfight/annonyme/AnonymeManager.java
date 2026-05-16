package fr.originsfight.annonyme;

import fr.originsfight.OriginsFightCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.NameTagVisibility;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AnonymeManager {

    private final OriginsFightCore plugin;
    private final Set<UUID> anonymousPlayers;
    private final Map<UUID, Team> originalTeams; // Map to store original teams
    private final Map<UUID, String> originalDisplayNames; // Map to store original display names
    private Team anonymousTeam;
    private File anonymousPlayersFile;
    private FileConfiguration anonymousPlayersConfig;
    private BukkitTask enforcementTask;

    public AnonymeManager(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.anonymousPlayers = new HashSet<>();
        this.originalTeams = new HashMap<>();
        this.originalDisplayNames = new HashMap<>(); // Initialize the map
        setupScoreboardTeam();
        setupAnonymousPlayersFile();
        loadAnonymousPlayers();
        startEnforcementTask();
    }

    /**
     * Tâche périodique qui réimpose l'appartenance à la team "anonymous"
     * toutes les 10 ticks. Nécessaire car les plugins de factions (FactionsUUID,
     * Saber-Factions) réassignent automatiquement les joueurs à leur team de
     * couleur de relation, ce qui écrase notre préfixe §k et révèle le pseudo.
     */
    private void startEnforcementTask() {
        enforcementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (anonymousPlayers.isEmpty()) return;
            for (UUID uuid : anonymousPlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) continue;
                if (!anonymousTeam.hasEntry(player.getName())) {
                    // Sauvegarder la team courante (ré-assignée par le plugin de faction)
                    // pour pouvoir la restaurer à la sortie du mode anonyme.
                    Team current = Bukkit.getScoreboardManager().getMainScoreboard()
                            .getEntryTeam(player.getName());
                    if (current != null && !current.equals(anonymousTeam)) {
                        originalTeams.put(uuid, current);
                        current.removeEntry(player.getName());
                    }
                    anonymousTeam.addEntry(player.getName());
                }
                // Le faction plugin peut aussi réécrire le displayName / playerListName,
                // on les ré-applique ici.
                String obf = "Identité masquée";
                if (!obf.equals(player.getDisplayName())) {
                    player.setDisplayName(obf);
                }
                if (!obf.equals(player.getPlayerListName())) {
                    try {
                        player.setPlayerListName(obf);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }, 10L, 10L);
    }

    private void setupScoreboardTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        anonymousTeam = scoreboard.getTeam("anonymous");
        if (anonymousTeam == null) {
            anonymousTeam = scoreboard.registerNewTeam("anonymous");
        }
        anonymousTeam.setPrefix("");
        anonymousTeam.setSuffix("");
        anonymousTeam.setCanSeeFriendlyInvisibles(false);
        anonymousTeam.setNameTagVisibility(NameTagVisibility.NEVER);
    }

    private void setupAnonymousPlayersFile() {
        anonymousPlayersFile = new File(plugin.getDataFolder(), "anonymous_players.yml");
        if (!anonymousPlayersFile.exists()) {
            try {
                anonymousPlayersFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create anonymous_players.yml: " + e.getMessage());
            }
        }
        anonymousPlayersConfig = YamlConfiguration.loadConfiguration(anonymousPlayersFile);
    }

    public void loadAnonymousPlayers() {
        if (anonymousPlayersConfig.isSet("anonymous")) {
            List<String> uuids = anonymousPlayersConfig.getStringList("anonymous");
            for (String uuidString : uuids) {
                try {
                    anonymousPlayers.add(UUID.fromString(uuidString));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID found in anonymous_players.yml: " + uuidString);
                }
            }
        }
        plugin.getLogger().info("Loaded " + anonymousPlayers.size() + " anonymous players.");
    }

    public void saveAnonymousPlayers() {
        List<String> uuids = new ArrayList<>();
        for (UUID uuid : anonymousPlayers) {
            uuids.add(uuid.toString());
        }
        anonymousPlayersConfig.set("anonymous", uuids);
        try {
            anonymousPlayersConfig.save(anonymousPlayersFile);
            plugin.getLogger().info("Saved " + anonymousPlayers.size() + " anonymous players.");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save anonymous_players.yml: " + e.getMessage());
        }
    }

    public boolean toggleAnonymity(Player player) {
        if (anonymousPlayers.contains(player.getUniqueId())) {
            removeAnonymous(player);
            return false;
        } else {
            addAnonymous(player);
            return true;
        }
    }

    public void addAnonymous(Player player) {
        anonymousPlayers.add(player.getUniqueId());

        // Store original team if player is already in a tea
        Team playerCurrentTeam = player.getScoreboard().getEntryTeam(player.getName());
        if (playerCurrentTeam != null) {
            originalTeams.put(player.getUniqueId(), playerCurrentTeam);
            playerCurrentTeam.removeEntry(player.getName());
        }

        anonymousTeam.addEntry(player.getName());

        // Store original display name and set masked one
        originalDisplayNames.put(player.getUniqueId(), player.getDisplayName());
        String obf = "Identité masquée";
        player.setDisplayName(obf);
        try {
            player.setPlayerListName(obf);
        } catch (IllegalArgumentException ignored) {}
        // Push état au client (override du rendu pseudo/faction/grade)
        AnonymousDataSender.broadcast(player, true);
    }

    public void removeAnonymous(Player player) {
        anonymousPlayers.remove(player.getUniqueId());
        anonymousTeam.removeEntry(player.getName());

        // Restore original team if one was stored
        Team originalTeam = originalTeams.remove(player.getUniqueId());
        if (originalTeam != null) {
            originalTeam.addEntry(player.getName());
        }

        // Restore original display name
        String originalName = originalDisplayNames.remove(player.getUniqueId());
        if (originalName != null) {
            player.setDisplayName(originalName);
        }
        // Restore tab list name (null = revert to actual player name)
        try {
            player.setPlayerListName(null);
        } catch (IllegalArgumentException ignored) {}
        // Force le client à effacer le cache anonyme pour ce joueur
        AnonymousDataSender.broadcast(player, false);
    }

    public boolean isAnonymous(Player player) {
        return anonymousPlayers.contains(player.getUniqueId());
    }

    public void onPlayerJoin(Player player) {
        if (isAnonymous(player)) {
            // Re-apply anonymity settings on join
            anonymousTeam.addEntry(player.getName());
            // Ensure display name is obfuscated on join
            originalDisplayNames.put(player.getUniqueId(), player.getDisplayName());
            String obf = "Identité masquée";
            player.setDisplayName(obf);
            try {
                player.setPlayerListName(obf);
            } catch (IllegalArgumentException ignored) {}
            player.sendMessage("§aVous êtes anonyme.");
            // Avertit tous les viewers que ce joueur est anonyme
            AnonymousDataSender.broadcast(player, true);
        }
        // Synchronise vers ce viewer l'état de tous les joueurs anonymes en ligne
        // (différé d'un tick pour laisser la connexion s'établir côté plugin channel).
        Bukkit.getScheduler().runTaskLater(plugin, () -> AnonymousDataSender.syncAll(player), 5L);
    }

    public void onPlayerQuit(Player player) {
        // Clean up from anonymous team if they quit while anonymous
        if (anonymousTeam.hasEntry(player.getName())) {
            anonymousTeam.removeEntry(player.getName());
        }
        // Remove from originalTeams and originalDisplayNames maps to prevent memory leaks
        originalTeams.remove(player.getUniqueId());
        originalDisplayNames.remove(player.getUniqueId());
    }

    public void disable() {
        if (enforcementTask != null) {
            enforcementTask.cancel();
            enforcementTask = null;
        }
        saveAnonymousPlayers();
        // Restore display names for any online anonymous players before disabling
        for (UUID uuid : anonymousPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (originalDisplayNames.containsKey(uuid)) {
                    player.setDisplayName(originalDisplayNames.get(uuid));
                }
                try {
                    player.setPlayerListName(null);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        // Clear all players from the anonymous team when the plugin disables
        for (String entry : anonymousTeam.getEntries()) {
            anonymousTeam.removeEntry(entry);
        }
        // Unregister the team to clean up the scoreboard
        anonymousTeam.unregister();
    }
}
